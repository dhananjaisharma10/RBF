import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.NodeInfo
import org.arl.unet.PDU

class LoadGenerator extends UnetAgent
{
    private List<Integer> destNodes     // list of possible destination nodes
    private float load                  // normalized load to generate

    private AgentID phy, rdp, node, mac
    private int myAddr

    LoadGenerator(List<Integer> destNodes, float load)
    {
        this.destNodes = destNodes
        this.load      = load
    }

    int networksize = ++destNodes.size()

    def dataMsg = PDU.withFormat
    {
        uint8('src')
        uint8('dst')
        uint16('store')
    }

    // Packet count.
    private int packetsent = 0

    @Override
    void setup()
    {
        register Services.ROUTING
    }

    @Override
    void startup() 
    {
        phy = agentForService Services.PHYSICAL
        subscribe(topic(phy))

        mac    = agentForService Services.MAC
        rdp    = agentForService Services.ROUTE_MAINTENANCE
        node   = agentForService Services.NODE_INFO
        myAddr = node.Address
        
        // in seconds.
        float dataPktDuration = get(phy, Physical.DATA, PhysicalChannelParam.frameDuration)
        
        //compute average packet arrival rate.
        float rate = load/dataPktDuration

        println "dataPktDuration = ${dataPktDuration} ${1000/rate}"

        /*add new PoissonBehavior(1000/rate, {
            
            // i is the one-fifth part of an interval
            int i = 1000/(rate*5)

            // j number of nodes will be transmitting at the same time
            int j = networksize/5

            if (myAddr <= j)
            {
                add new WakerBehavior(i, {
                // send Route Discovery Request here.
                rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
                })
            } 
            if (myAddr > j && myAddr <= 2*j)
            {
                add new WakerBehavior(2*i, {
                // send Route Discovery Request here.
                rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
                })
            }
            if (myAddr > 2*j && myAddr <= 3*j)
            {
                add new WakerBehavior(3*i, {
                // send Route Discovery Request here.
                rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
                })
            }
            if (myAddr > 3*j && myAddr <= 4*j)
            {
                add new WakerBehavior(4*i, {
                // send Route Discovery Request here.
                rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
                })
            }
            if (myAddr > 4*j && myAddr <= 5*j)
            {
                add new WakerBehavior(5*i, {
                // send Route Discovery Request here.
                rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 50, count: 1)
                })
            }
            })*/

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
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == true)
        {
            int fd  = msg.getTo()
            int hop = msg.getNextHop()
            phy << new ClearReq()   // clear any on-going transmission.
            rxDisable()
            phy << new TxFrameReq(to: hop, type: Physical.DATA, protocol: Protocol.DATA, data: dataMsg.encode([src: myAddr, dst: fd, store: ++packetsent]))
            println("SENT!")
            add new WakerBehavior(1000, {rxEnable()})
        }
    }
}