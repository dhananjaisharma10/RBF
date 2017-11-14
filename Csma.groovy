import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.NodeInfo

class Csma extends UnetAgent
{
  private AgentID node, phy

  private int myAddr

  private final static int MAX_BACKOFF_SLOTS = 29
  private final static int MIN_BACKOFF_SLOTS = 3
  private final static int MAX_RETRY_COUNT   = 10

  private final static float BACKOFF_RANDOM = 3.seconds

  private final static int MAX_QUEUE_LEN = 16

  Queue<ReservationReq> queue = new ArrayDeque<ReservationReq>()

  private enum State {
    IDLE, WAIT, SENSING, BACKOFF
  }

  private enum Event {
    RESERVATION_REQ, RX_CTRL, RX_DATA, SNOOP_CTRL, SNOOP_DATA
  }
    
  private FSMBehavior fsm = FSMBuilder.build
  {
    int retryCount = 0
    float backoff = 0

    state(State.IDLE) {   // State
      action {
        if (!queue.isEmpty()) {
          setNextState(State.SENSING)
        }
        block()
      }
    }

    state(State.WAIT) {   // State
      onEnter {
        after(rnd(0, BACKOFF_RANDOM)) {
          setNextState(State.SENSING)
        }
      }
    }

    state(State.BACKOFF) {  // State
      onEnter {
        after(backoff.milliseconds) {
          setNextState(State.SENSING)
        }
      }

      onEvent(Event.RX_CTRL) {
        backoff = backoff - currentTimeMillis() + controlMsgDuration
        reenterState()
      }

      onEvent(Event.SNOOP_CTRL) {
        backoff = backoff - currentTimeMillis() + controlMsgDuration + 300
        reenterState()
      }

      onEvent(Event.RX_DATA) {
        backoff = backoff - currentTimeMillis() + dataMsgDuration
        reenterState()
      }

      onEvent(Event.SNOOP_DATA) {
        backoff = backoff - currentTimeMillis() + dataMsgDuration + 1000
        reenterState()
      }
    }

    state(State.SENSING) {  // State
      onEnter {
        if (phy.busy) { // This would only be for receiving any packets.
          if (retryCount == MAX_RETRY_COUNT) {
            sendReservationStatusNtf(queue.poll(), ReservationStatus.FAILURE)
            retryCount = 0
            setNextState(State.IDLE)
          }
          else if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            Message msg = queue.peek()
            backoff = getNumberofSlots(retryCount)*msg.duration*1000
            setNextState(State.BACKOFF)
          }
        }
        else {  // Send Ntf
          ReservationReq msg = queue.poll()
          retryCount = 0
          rxDisable()
          sendReservationStatusNtf(msg, ReservationStatus.START)
          after(msg.duration) {
            sendReservationStatusNtf(msg, ReservationStatus.END)
            rxEnable()
            setNextState(State.IDLE)
          }
        }
      }
    }
  }

  private int getNumberofSlots(int slots)
  {
    int product = 1
    while(slots > 0) {
      product = product*2   // exponential backoff
      slots--
    }
    return AgentLocalRandom.current().nextInt(product)
    //return AgentLocalRandom.current().nextInt(MAX_BACKOFF_SLOTS) + MIN_BACKOFF_SLOTS
  }

  @Override
  void setup()
  {
    register Services.MAC
  }

  @Override
  void startup()
  {
    phy = agentForService(Services.PHYSICAL)
    subscribe phy
    subscribe(topic(phy, Physical.SNOOP))

    node = agentForService(Services.NODE_INFO)
    myAddr = node.Address
    add(fsm)
  }

  @Override
  Message processRequest(Message msg)
  {
    switch (msg)
    {
      case ReservationReq:
      if (msg.duration <= 0) return new Message(msg, Performative.REFUSE)
      if (queue.size() >= MAX_QUEUE_LEN || msg.duration > maxReservationDuration) return new Message(msg, Performative.REFUSE)
      queue.add(msg)            // add this ReservationReq in the queue
      if (!getChannelBusy())    // channel is in IDLE state
      {
        fsm.restart()           // restart fsm
      }
      return new ReservationRsp(msg)
      case ReservationCancelReq:
      case ReservationAcceptReq:                                  // respond to other requests defined
      case TxAckReq:                                              //  by the MAC service trivially with
        return new Message(msg, Performative.REFUSE)            //  a REFUSE performative
    }
    return null
  }

  @Override
  void processMessage(Message msg)
  {
    if (msg instanceof RxFrameNtf)
    {
      if (msg.type == Physical.CONTROL)
      {
        fsm.trigger(msg.to == myAddr ? Event.RX_CTRL : Event.SNOOP_CTRL)
      }
      if (msg.type == Physical.DATA)
      {
        fsm.trigger(msg.to == myAddr ? Event.RX_DATA : Event.SNOOP_DATA)
      }
    }
  }

  private void rxDisable()
  {
    //DisableReceiver
    ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
    req.get(PhysicalParam.rxEnable)
    ParameterRsp rsp = (ParameterRsp) request(req, 1000)            
    rsp.set(PhysicalParam.rxEnable,false) 
  }

  private void rxEnable()
  {
    //EnableReceiver
    ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
    req.get(PhysicalParam.rxEnable)
    ParameterRsp rsp = (ParameterRsp)request(req, 1000)         
    rsp.set(PhysicalParam.rxEnable,true) 
  }

  final float maxReservationDuration = 65.535

  boolean getChannelBusy() {                      // channel is considered busy if fsm is not IDLE
    return fsm.currentState.name != State.IDLE
  }

  private void sendReservationStatusNtf(ReservationReq msg, ReservationStatus status) {
    send new ReservationStatusNtf(recipient: msg.sender, requestID: msg.msgID, to: msg.to, from: myAddr, status: status)
  }

  int controlMsgDuration
  int dataMsgDuration

}