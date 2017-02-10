// See LICENSE for license details.

package chiselTests

// Keep Chisel._ separate from chisel3._ below
object CompatibilityComponents {
  import Chisel._

  class ChiselBundle extends Bundle {
    val a = UInt(width = 32)
  }

  class ChiselModuleA extends Module {
    val io = new Bundle {
      val foo = new ChiselBundle
    }
    io.foo.a := UInt(123)
  }
  class ChiselModuleB extends Module {
    val io = new Bundle {
      val foo = (new ChiselBundle).flip
    }
    assert(io.foo.a === UInt(123))
  }
}

object Chisel3Components {
  import chisel3._

  class Chisel3Bundle extends Bundle {
    val a = Output(UInt(32.W))
  }

  class Chisel3ModuleA extends Module {
    val io = IO(new Bundle {
      val foo = new Chisel3Bundle
    })
    io.foo.a := 123.U
  }
  class Chisel3ModuleB extends Module {
    val io = IO(new Bundle {
      val foo = Flipped(new Chisel3Bundle)
    })
    assert(io.foo.a === 123.U)
  }
}

class CompatibiltyInteroperabilitySpec extends ChiselFlatSpec {

  "Bundles defined in the Chisel compatibility package" should
			"successfully bulk connect in chisel3._" in {
		import chisel3._
		import chisel3.testers.BasicTester
    import CompatibilityComponents._

    class InterfaceTop extends BasicTester {
      val a = Module(new ChiselModuleA)
      val b = Module(new ChiselModuleB)
      b.io.foo <> a.io.foo
      stop()
    }
    assertTesterPasses { new InterfaceTop }
  }

  "Bundles defined in the chisel3._" should "successfully bulk connect in Chisel._" in {
		import Chisel._
		import chisel3.testers.BasicTester
    import Chisel3Components._

    class InterfaceTop extends BasicTester {
      val a = Module(new Chisel3ModuleA)
      val b = Module(new Chisel3ModuleB)
      b.io.foo <> a.io.foo
      stop()
    }
    assertTesterPasses { new InterfaceTop }
  }

  "Similar Bundles defined in the chisel3._ and Chisel._" should
      "successfully bulk connect in chisel3._" in {
		import chisel3._
		import chisel3.testers.BasicTester
    import Chisel3Components._
    import CompatibilityComponents._

    class InterfaceTopA extends BasicTester {
      val a = Module(new ChiselModuleA)
      val b = Module(new Chisel3ModuleB)
      b.io.foo <> a.io.foo
      stop()
    }
    class InterfaceTopB extends BasicTester {
      val a = Module(new Chisel3ModuleA)
      val b = Module(new ChiselModuleB)
      b.io.foo <> a.io.foo
      stop()
    }
    assertTesterPasses { new InterfaceTopA }
    assertTesterPasses { new InterfaceTopB }
  }
}
