import chisel3._
import chisel3.util._
import lib._

/**
 * Determines the smallest priority with the lowest index among the input values.
 * Outputs both the value and index
 * @param n number of priorities to compare
 * @param nWid width of the normal priority field
 * @param cWid width of the cyclic priority field
 */
class MinFinder(n: Int, nWid : Int, cWid : Int, rWid: Int) extends Module{
  val io = IO(new Bundle{
    val values = Input(Vec(n, new PriorityAndID(nWid,cWid,rWid)))
    val res = Output(new PriorityAndID(nWid,cWid,rWid))
    val idx = Output(UInt(log2Ceil(n).W))
  })

  class Dup extends Bundle {
    val v = new PriorityAndID(nWid,cWid,rWid)
    val idx = UInt(log2Ceil(n).W)
  }

  // bundle input values with their corresponding index
  val inDup = Wire(Vec(n, new Dup()))
  for (i <- 0 until n) {
    inDup(i).v := io.values(i)
    inDup(i).idx := i.U
  }

  // create a reduced tree structure to find the minimum value
  // lowest cyclic priority wins
  // if cyclic priorities are equal the normal priority decides
  // if both are equal the index decides
  val res = inDup.reduceTree((x: Dup, y: Dup) => Mux((x.v.prio.cycl<y.v.prio.cycl) || (x.v.prio.cycl===y.v.prio.cycl && (x.v.prio.norm < y.v.prio.norm || (x.v.prio.norm===y.v.prio.norm && x.idx<y.idx))),x,y))

  io.res := res.v
  io.idx := res.idx
}
