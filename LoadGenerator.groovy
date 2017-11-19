import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.nodeinfo.*

class LoadGenerator extends UnetAgent
{
    private List<Integer> destNodes     // list of possible destination nodes
    private float load                  // normalized load to generate

    private AgentID phy, rdp, node
    private int myAddr

    LoadGenerator(List<Integer> destNodes, float load)
    {
        this.destNodes = destNodes
        this.load      = load
    }

    def dataMsg = PDU.withFormat
    {
        uint8('source')
        uint8('destination')
        uint16('dataPktId')
    }

    // TIME taken to traverse one hop. Includes queueing delays, propagation delay and some buffer.
    final static int NODE_TRAVERSAL_TIME = 5000

    int packetsent = 0                          // packet count for DATA packets.
    int networkSize = ++destNodes.size()        // number of nodes in the network.

    // Round Trip Time (RTT) to traverse the whole network.
    int netTraversalTime = 2*NODE_TRAVERSAL_TIME*networkSize

    ArrayList<Integer> dataPktList = new ArrayList<Integer>()

    @Override
    void setup()
    {
        register Services.ROUTING
    }

    @Override
    void startup() 
    {
        rdp    = agentForService Services.ROUTE_MAINTENANCE
        node   = agentForService Services.NODE_INFO
        myAddr = node.Address
        phy = agentForService Services.PHYSICAL
        subscribe phy

        float dataPktDuration = get(phy, Physical.DATA, PhysicalChannelParam.frameDuration) // in seconds.
        //compute average packet arrival rate.
        float rate = load/dataPktDuration

        // creating poisson intervals
        add new PoissonBehavior(1000/rate, {
            rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
            })
            
    }

    private void rxDisable()
    {
        // Disable Receiver
        ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
        req.get(PhysicalParam.rxEnable)
        ParameterRsp rsp = (ParameterRsp) request(req, 1000)            
        rsp.set(PhysicalParam.rxEnable,false) 
    }

    private void rxEnable()
    {
        // Enable Receiver
        ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
        req.get(PhysicalParam.rxEnable)
        ParameterRsp rsp = (ParameterRsp)request(req, 1000)         
        rsp.set(PhysicalParam.rxEnable,true) 
    }

    @Override
    void processMessage(Message msg) 
    {
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == true)    // Reliable RouteDiscoveryNtf
        {
            phy << new ClearReq()   // clear any on-going transmission.
            rxDisable()
            def bytes = dataMsg.encode([source: myAddr, destination: msg.to, dataPktId: ++packetsent])
            phy << new TxFrameReq(to: msg.nextHop, type: Physical.DATA, protocol: Protocol.DATA, data: bytes)

            println(myAddr+" SADA at "+currentTimeMillis())

            // Enable receiver after 417 ms (dataMsgDuration at 2400 bps).
            add new WakerBehavior(417, {rxEnable()})

            // Add this packet into the data packet list.
            dataPktList.add(packetsent)

            // Check for this packet in the dataPkyList after net traversal time.
            // If it is still there, send an unreliable RouteDiscoveryNtf for this destination.
            add new WakerBehavior(netTraversalTime,
            {
                if (dataPktList.contains(packetsent))
                {
                    println("PACKET NOT DELIVERED")

                    RouteDiscoveryNtf ntf = new RouteDiscoveryNtf(
                        recipient:   msg.sender,
                        to:          msg.to, 
                        nextHop:     msg.nextHop,
                        reliability: false)
                    send ntf
                }
                })
        }

        if (msg instanceof TxFrameNtf)
        {
            if (msg.type == Physical.DATA)
            {
                println("DATAPKT")
            }
        }

        // ACK packets.
        if (msg instanceof RxFrameNtf && msg.protocol == Protocol.USER)
        {
            def info = dataMsg.decode(msg.data) // decode
            int os = info.source                // original source

            if (myAddr == os)       // I am the OS.
            {
                println(myAddr+" ACK RECEIVED for "+info.dataPktId)
                // remove packet id from the data packet list.
                for (int i = 0; i < dataPktList.size(); i++) {if (dataPktList.get(i) == info.dataPktId) {dataPktList.remove(i)}}
            }
        }
    }
}