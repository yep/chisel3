// See LICENSE for license details.

package chisel3.core

import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.collection.JavaConversions._
import scala.language.experimental.macros

import java.util.IdentityHashMap

import chisel3.internal._
import chisel3.internal.Builder._
import chisel3.internal.firrtl._
import chisel3.internal.sourceinfo.{InstTransform, SourceInfo}

object Module {
  /** A wrapper method that all Module instantiations must be wrapped in
    * (necessary to help Chisel track internal state).
    *
    * @param bc the Module being created
    *
    * @return the input module `m` with Chisel metadata properly set
    */
  def apply[T <: BaseModule](bc: => T): T = macro InstTransform.apply[T]

  def do_apply[T <: BaseModule](bc: => T)(implicit sourceInfo: SourceInfo): T = {
    if (Builder.readyForModuleConstr) {
      throwException("Error: Called Module() twice without instantiating a Module." +
                     sourceInfo.makeMessage(" See " + _))
    }
    Builder.readyForModuleConstr = true

    val parent = Builder.currentModule
    val whenDepth: Int = Builder.whenDepth

    // Save then clear clock and reset to prevent leaking scope, must be set again in the Module
    val clockAndReset: Option[ClockAndReset] = Builder.currentClockAndReset
    Builder.currentClockAndReset = None

    // Execute the module, this has the following side effects:
    //   - set currentModule
    //   - unset readyForModuleConstr
    //   - reset whenDepth to 0
    //   - set currentClockAndReset
    val module: T = bc  // bc is actually evaluated here

    if (Builder.whenDepth != 0) {
      throwException("Internal Error! When depth is != 0, this should not be possible")
    }
    if (Builder.readyForModuleConstr) {
      throwException("Error: attempted to instantiate a Module, but nothing happened. " +
                     "This is probably due to rewrapping a Module instance with Module()." +
                     sourceInfo.makeMessage(" See " + _))
    }
    Builder.currentModule = parent // Back to parent!
    Builder.whenDepth = whenDepth
    Builder.currentClockAndReset = clockAndReset // Back to clock and reset scope

    val component = module.generateComponent()
    Builder.components += component

    // Handle connections at enclosing scope
    if(!Builder.currentModule.isEmpty) {
      pushCommand(DefInstance(sourceInfo, module, component.ports))
      module.initializeInParent()
    }
    module
  }

  /** Returns the implicit Clock */
  def clock: Clock = Builder.forcedClock
  /** Returns the implicit Reset */
  def reset: Bool = Builder.forcedReset
}

/** Abstract base class for Modules, an instantiable organizational unit for RTL.
  */
// TODO: seal this?
abstract class BaseModule extends HasId {
  //
  // Builder Internals - this tracks which Module RTL construction belongs to.
  //
  if (!Builder.readyForModuleConstr) {
    throwException("Error: attempted to instantiate a Module without wrapping it in Module().")
  }
  readyForModuleConstr = false

  Builder.currentModule = Some(this)
  Builder.whenDepth = 0

  //
  // Module Construction Internals
  //
  protected var _closed = false

  // Fresh Namespace because in Firrtl, Modules namespaces are disjoint with the global namespace
  private[core] val _namespace = Namespace.empty
  private val _ids = ArrayBuffer[HasId]()
  private[chisel3] def addId(d: HasId) {
    require(!_closed, "Can't write to module after module close")
    _ids += d
  }
  protected def getIds = {
    require(_closed, "Can't get ids before module close")
    _ids.toSeq
  }

  private val _ports = new ArrayBuffer[Data]()
  // getPorts unfortunately already used for tester compatibility
  protected def getModulePorts = {
    require(_closed, "Can't get ports before module close")
    _ports.toSeq
  }

  // These methods allow checking some properties of ports before the module is closed,
  // mainly for compatibility purposes.
  protected def portsContains(elem: Data): Boolean = _ports contains elem
  protected def portsSize: Int = _ports.size

  /** Generates the FIRRTL Component (Module or Blackbox) of this Module.
    * Also closes the module so no more construction can happen inside.
    */
  private[core] def generateComponent(): Component

  /** Sets up this module in the parent context
    */
  private[core] def initializeInParent()

  //
  // Chisel Internals
  //
  /** Desired name of this module. Override this to give this module a custom, perhaps parametric,
    * name.
    */
  def desiredName = this.getClass.getName.split('.').last

  /** Legalized name of this module. */
  final val name = Builder.globalNamespace.name(desiredName)

  /** Called at the Module.apply(...) level after this Module has finished elaborating.
    * Returns a map of nodes -> names, for named nodes.
    *
    * Helper method.
    */
  protected def nameIds(rootClass: Class[_]): HashMap[HasId, String] = {
    val names = new HashMap[HasId, String]()

    def name(node: HasId, name: String) {
      // First name takes priority, like suggestName
      // TODO: DRYify with suggestName
      if (!names.contains(node)) {
        names.put(node, name)
      }
    }

    /** Recursively suggests names to supported "container" classes
      * Arbitrary nestings of supported classes are allowed so long as the
      * innermost element is of type HasId
      * (Note: Map is Iterable[Tuple2[_,_]] and thus excluded)
      */
    def nameRecursively(prefix: String, nameMe: Any): Unit =
      nameMe match {
        case (id: HasId) => name(id, prefix)
        case Some(elt) => nameRecursively(prefix, elt)
        case (iter: Iterable[_]) if iter.hasDefiniteSize =>
          for ((elt, i) <- iter.zipWithIndex) {
            nameRecursively(s"${prefix}_${i}", elt)
          }
        case _ => // Do nothing
      }

    /** Scala generates names like chisel3$util$Queue$$ram for private vals
      * This extracts the part after $$ for names like this and leaves names
      * without $$ unchanged
      */
    def cleanName(name: String): String = name.split("""\$\$""").lastOption.getOrElse(name)

    for (m <- getPublicFields(rootClass)) {
      nameRecursively(cleanName(m.getName), m.invoke(this))
    }

    names
  }

  /** Compatibility function. Allows Chisel2 code which had ports without the IO wrapper to
    * compile under Bindings checks. Does nothing in non-compatibility mode.
    *
    * Should NOT be used elsewhere. This API will NOT last.
    *
    * TODO: remove this, perhaps by removing Bindings checks in compatibility mode.
    */
  def _autoWrapPorts() {}

  //
  // BaseModule User API functions
  //
  protected def annotate(annotation: ChiselAnnotation): Unit = {
    Builder.annotations += annotation
  }

  /**
   * This must wrap the datatype used to set the io field of any Module.
   * i.e. All concrete modules must have defined io in this form:
   * [lazy] val io[: io type] = IO(...[: io type])
   *
   * Items in [] are optional.
   *
   * The granted iodef WILL NOT be cloned (to allow for more seamless use of
   * anonymous Bundles in the IO) and thus CANNOT have been bound to any logic.
   * This will error if any node is bound (e.g. due to logic in a Bundle
   * constructor, which is considered improper).
   *
   * Also registers a Data as a port, also performing bindings. Cannot be called once ports are
   * requested (so that all calls to ports will return the same information).
   * Internal API.
   *
   * TODO(twigg): Specifically walk the Data definition to call out which nodes
   * are problematic.
   */
  protected def IO[T<:Data](iodef: T): iodef.type = {
    require(!_closed, "Can't add more ports after module close")
    requireIsChiselType(iodef, "io type")

    // Compatibility code: Chisel2 did not require explicit direction on nodes
    // (unspecified treated as output, and flip on nothing was input).
    // This sets assigns the explicit directions required by newer semantics on
    // Bundles defined in compatibility mode.
    // This recursively walks the tree, and assigns directions if no explicit
    // direction given by upper-levels (override Input / Output) AND element is
    // directly inside a compatibility Bundle determined by compile options.
    def assignCompatDir(data: Data, insideCompat: Boolean): Unit = {
      data match {
        case data: Element if insideCompat => data._assignCompatibilityExplicitDirection
        case data: Element => // Not inside a compatibility Bundle, nothing to be done
        case data: Aggregate => data.userDirection match {
          // Recurse into children to ensure explicit direction set somewhere
          case UserDirection.Unspecified | UserDirection.Flip => data match {
            case data: Record if (!data.compileOptions.dontAssumeDirectionality) =>
              data.getElements.foreach(assignCompatDir(_, true))
            case _ => data.getElements.foreach(assignCompatDir(_, false))
          }
          case UserDirection.Input | UserDirection.Output => // forced assign, nothing to do
        }
      }
    }
    assignCompatDir(iodef, false)

    // Bind each element of the iodef to being a Port
    iodef.bind(PortBinding(this))
    _ports += iodef
    iodef
  }

  //
  // Internal Functions
  //

  /** Keep component for signal names */
  private[chisel3] var _component: Option[Component] = None

  /** Signal name (for simulation). */
  override def instanceName =
    if (_parent == None) name else _component match {
      case None => getRef.name
      case Some(c) => getRef fullName c
    }

}
