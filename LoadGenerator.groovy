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

    private final static int NODE_TRAVERSAL_TIME = 5000

    private int packetsent = 0  // packet count.
    private int networkSize = ++destNodes.size()
    private int NET_TRAVERSAL_TIME  = 2*NODE_TRAVERSAL_TIME*networkSize

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
        //rtr    = agentForService Services.ROUTING
        node   = agentForService Services.NODE_INFO
        myAddr = node.Address
        phy = agentForService Services.PHYSICAL
        subscribe phy

        float dataPktDuration = get(phy, Physical.DATA, PhysicalChannelParam.frameDuration) // in seconds.
        //compute average packet arrival rate.
        float rate = load/dataPktDuration
        println "dataPktDuration = ${dataPktDuration} ${1000/rate}"
        add new PoissonBehavior(1000/rate, {
            rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
            })
            
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

    @Override
    void processMessage(Message msg) 
    {
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == true)    // Reliable RouteDiscoveryNtf
        {
            phy << new ClearReq()   // clear any on-going transmission.
            rxDisable()
            def bytes = dataMsg.encode([source: myAddr, destination: msg.to, dataPktId: ++packetsent])
            phy << new TxFrameReq(to: msg.nextHop, type: Physical.DATA, protocol: Protocol.DATA, data: bytes)
            add new WakerBehavior(1000, {rxEnable()})
            dataPktList.add(packetsent)

            add new WakerBehavior(NET_TRAVERSAL_TIME, {
                if (dataPktList.contains(packetsent))
                {
                    println("PACKET NOT DELIVERED")
                    /*  If the node does not recieve an ACK in NET_TRAVERSAL_TIME of the network,
                    *   send a RouteDiscoveryNtf for the same destination with false reliability.
                    */
                    RouteDiscoveryNtf ntf = new RouteDiscoveryNtf(
                        recipient:   msg.sender,
                        to:          msg.to, 
                        nextHop:     msg.nextHop,
                        reliability: false)
                    send ntf
                }
                })
            println(myAddr+" packet no: "+packetsent+" SENT! at "+currentTimeMillis())
        }

        if (msg instanceof RxFrameNtf && msg.protocol == Protocol.USER) // ACK packets.
        {
            def info = dataMsg.decode(msg.data)
            int os = info.source

            if (myAddr == os)       // I am the OS.
            {
                println(myAddr+" ACK RECEIVED for "+info.dataPktId)
                for (int i = 0; i < dataPktList.size(); i++) {if (dataPktList.get(i) == info.dataPktId) {dataPktList.remove(i)}}
            }
        }
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == false)
        {
            println("ANIMAL")
        }
    }
}