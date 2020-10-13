import chisel3._
import chisel3.util._
import lib._

/**
 * Component implementing the heapify algorithm. Works itself either upwards or downwards
 * through the heap from a given start index and swaps elements to
 * satisfy the min-heap condition.
 *
 * During operation either of the control signals heapifyUp or heapifyDown need to be held high
 * until done is asserted.
 *
 * When done is asserted, the component also signalizes whether a swap has taken place for that clock cycle
 * @param size the size of the heap
 * @param chCount the number of children per node (must be power of 2)
 * @param nWid the width of the normal priority
 * @param cWid the width of the cyclic priority
 */
class Heapifier(
                 size : Int, // max number of elements in queue
                 chCount : Int, // Number of children per node. Must be 2^m
                 nWid : Int, // Width of normal priority
                 cWid : Int, // Width of cyclic priority
                 rWid: Int // reference width
                )extends Module{
  val io = IO(new Bundle{
    val control = new Bundle {
      val heapifyUp = Input(Bool())
      val heapifyDown = Input(Bool())
      val done = Output(Bool())
      val swapped = Output(Bool())
      val index = Input(UInt(log2Ceil(size).W))
      val heapSize = Input(UInt(log2Ceil(size+1).W))
    }

    val rdRam = new Bundle{
      val index = Output(UInt(log2Ceil(size/chCount).W))
      val data = Input(Vec(chCount, new PriorityAndID(nWid, cWid, rWid)))
      val mode = Output(Bool())
    }
    val wrRam = new Bundle{
      val index = Output(UInt(log2Ceil(size/chCount).W))
      val data = Output(new PriorityAndID(nWid, cWid, rWid))
      val write = Output(Bool())
    }

    // port to the cached head element stored in a register
    val headPort = new Bundle{
      val rdData = Input(new Priority(nWid,cWid))
      val wrData = Output(new Priority(nWid,cWid))
      val write = Output(Bool())
    }

    // TODO: remove debug outputs
    val out = Output(UInt((log2Ceil(chCount)+1).W))
    val swap = Output(Bool())
    val state = Output(UInt())
    val minInputs = Output(Vec(chCount+1,new Priority(nWid,cWid)))
    val parentOff = Output(UInt(log2Ceil(size).W))
    val nextIndexOut = Output(UInt(log2Ceil(size).W))
    val indexOut = Output(UInt(log2Ceil(size).W))
  })

  // state elements
  val idle :: warmUp1 :: warmDown1 :: warmUp2 :: warmDown2 :: readUp :: readDown :: wbUp1 :: wbDown1 :: wbUp2 :: wbDown2 :: Nil = Enum(11)
  val stateReg = RegInit(idle) // state register
  val indexReg = RegInit(0.U(log2Ceil(size).W)) // register holding the index of the current parent
  val swappedReg = RegInit(false.B) // register holding a flag showing whether a swap has occurred
  val parentReg = RegInit(VecInit(Seq.fill(chCount)(0.U.asTypeOf(new PriorityAndID(nWid,cWid,rWid))))) // register holding the content of the RAM cell containing the parent
  val childrenReg = RegInit(VecInit(Seq.fill(chCount)(0.U.asTypeOf(new PriorityAndID(nWid,cWid,rWid))))) // register holding the content of the RAM cell of the children

  // modules
  val minFinder = Module(new MinFinder(chCount + 1, nWid, cWid, rWid)) // module to find the minimum priority among the parent and children

  // ram address generation
  val addressIndex = Wire(UInt(log2Ceil(size).W)) // wire that address generation is based on. Is set to indexReg except of the last write back stage, where the next address needs to be generated
  addressIndex := indexReg
  val indexParent = addressIndex
  val indexChildren = (addressIndex << log2Ceil(chCount).U).asUInt + 1.U

  // parent selection
  val parentOffset = Mux(indexReg === 0.U, 0.U, indexReg(log2Ceil(chCount),0) - 1.U(log2Ceil(size).W)) // the offset of the parent within its RAM cell
  val parent = parentReg(parentOffset) // the actual parent selected from the parent register

  // hook up the minFinder
  minFinder.io.values(0) := parent
  io.minInputs(0) := parent
  for(i <- 0 until chCount){
    minFinder.io.values(i + 1) := childrenReg(i)
    io.minInputs(i+1) := childrenReg(i).prio //TODO: remove debug outputs
  }

  val nextIndexUp = ((indexReg - 1.U) >> log2Ceil(chCount)).asUInt() // index of next parent is given by (index-1)/childrenCount
  val nextIndexDown = (indexReg << log2Ceil(chCount)).asUInt() + RegNext(minFinder.io.idx) // index of next parent is given by (index * childrenCount) + selected child
  val swapRequired = minFinder.io.idx =/= 0.U // a swap is only required when the parent does not have the highest priority


  // default assignments
  io.control.done := false.B
  io.control.swapped := swappedReg
  io.rdRam.index := 0.U
  io.rdRam.mode := false.B
  io.wrRam.index := 0.U
  io.wrRam.data := parentReg
  io.wrRam.write := false.B
  io.headPort.write := false.B
  io.headPort.wrData := parentReg(0)

  // TODO: remove debug outputs
  io.out := minFinder.io.idx
  io.swap := swapRequired
  io.state := stateReg
  io.parentOff := parentOffset
  io.nextIndexOut := Mux(io.control.heapifyDown,nextIndexDown,nextIndexUp)
  io.indexOut := indexReg

  // the state machine is separated into 2 switch statements:
  // - one dealing with the state flow and control of state flow relevant elements
  // - the other one dealing with data flow and bus operation

  // state machine flow
  switch(stateReg){
    is(idle){ // in idle we wait for a control signal, update out index register, and hold the swapped flag low
      io.control.done := true.B
      indexReg := io.control.index
      swappedReg := false.B
      when(io.control.heapifyUp){
        stateReg := warmUp1
      }.elsewhen(io.control.heapifyDown){
        stateReg := warmDown1
      }.otherwise{
        stateReg := idle
      }
    }
    is(warmUp1){
      stateReg := warmUp2
    }
    is(warmUp2){
      stateReg := readUp
    }
    is(readUp){
      stateReg := wbUp1
    }
    is(wbUp1){
      stateReg := wbUp2
      when(!swapRequired){ // when no swap is required we go into idle state
        //io.control.done := true.B
        stateReg := idle
      }.otherwise{ // we have swapped
        swappedReg := true.B
      }
    }
    is(wbUp2){ // update the index register and apply new index to address generation
      stateReg := readUp
      indexReg := nextIndexUp
      addressIndex := nextIndexUp
      when(indexReg === 0.U){ // we have reached the root and can go to idle
        //io.control.done := true.B
        stateReg := idle
      }
    }
    is(warmDown1){
      stateReg := warmDown2
    }
    is(warmDown2){
      stateReg := readDown
    }
    is(readDown){
      stateReg := wbDown1
    }
    is(wbDown1){
      stateReg := wbDown2
      when(!swapRequired){ // when no swap is required we go into idle state
        //io.control.done := true.B
        stateReg := idle
      }.otherwise{ // we have swapped
        swappedReg := true.B
      }
    }
    is(wbDown2){ // update the index register and apply new index to address generation
      stateReg := readDown
      indexReg := nextIndexDown
      addressIndex := nextIndexDown
      when((nextIndexDown<<log2Ceil(chCount)).asUInt >= io.control.heapSize){ // we have reached a childless index and can go to idle
        //io.control.done := true.B
        stateReg := idle
      }
    }
  }
/*
  // data and bus control
  switch(stateReg){
    /////////////////////////////// up control
    is(warmUp1){ // apply childrens RAM address to read port
      io.rdRam.index := indexChildren
    }
    is(warmUp2){ // apply parents RAM address to read port and save children
      io.rdRam.index := indexParent
      childrenReg := io.rdRam.data
    }
    is(readUp){ // apply childrens RAM address to write port
      io.wrRam.index := indexChildren
      when(indexReg === 0.U){ // if parent is head -> use head port
        parentReg := parentReg
        parentReg(0.U) := io.headPort.rdData
      }.otherwise{ // if not read from RAM
        parentReg := io.rdRam.data
      }
    }
    is(wbUp1){ // write back the updated children RAM cell if a swap is required and update the parent register
      io.wrRam.index := indexParent
      when(swapRequired){
        parentReg := parentReg
        parentReg(parentOffset) := minFinder.io.res
        io.wrRam.data := parent //FIXME: write index is unknown one clock before
        io.wrRam.write := true.B
      }
    }
    is(wbUp2){ // write back the parent register and transfer the parent RAM cell to the children register
      io.ramReadPort.address := ramAddressParent
      childrenReg := parentReg
      when(swapRequired){
        when(indexReg === 0.U){ // write via head port if parent is head
          io.headPort.wrData := minFinder.io.res
          io.headPort.write := true.B
        }.otherwise{ // else use the RAM port
          io.ramWritePort.data := parentReg
          io.ramWritePort.write := true.B
        }
      }
    }
    ////////////////////////////// down control
    is(warmDown1){ // apply parents RAM address to read port
      io.ramReadPort.address := ramAddressParent
    }
    is(warmDown2){ // apply childrens RAM address to read port and save parent
      io.ramReadPort.address := ramAddressChildren
      when(indexReg === 0.U){ // if parent is head -> use head port
        parentReg := parentReg
        parentReg(0.U) := io.headPort.rdData
      }.otherwise{ // if not read from RAM
        parentReg := io.ramReadPort.data
      }
    }
    is(readDown){ // apply parents RAM address to write port and save children
      io.ramWritePort.address := ramAddressParent
      childrenReg := io.ramReadPort.data
    }
    is(wbDown1){ // write back the updated parent RAM cell if a swap is required and update the children register
      io.ramWritePort.address := ramAddressChildren
      when(swapRequired){
        childrenReg := childrenReg
        childrenReg(minFinder.io.idx - 1.U) := parent
        when(indexReg === 0.U){
          io.headPort.wrData := minFinder.io.res
          io.headPort.write := true.B
        }.otherwise{
          io.ramWritePort.data := parentReg
          io.ramWritePort.data(parentOffset) := minFinder.io.res
          io.ramWritePort.write := true.B
        }
      }
    }
    is(wbDown2){ // write back the children register and transfer the children RAM cell to the parent register
      io.ramReadPort.address := ramAddressChildren
      parentReg := childrenReg
      when(swapRequired){
        io.ramWritePort.data := childrenReg
        io.ramWritePort.write := true.B
      }
    }
  }
*/
}
