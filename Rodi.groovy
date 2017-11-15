import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.*

class Rodi extends UnetAgent
{
    private class RoutingInformation
    {
        private int destinationAddress
        private int nh
    }

    private class PacketHistory
    {
        private int requestIDNo
        private int sendernode
    }

    private class Attempt
    {
        private int des
        private int num
    }

    private class TxReserve
    {
        private TxFrameReq txreq
        private ReservationReq resreq
    }

    // PDU for CONTROL packets: RREQ and RREP.
    private final static PDU pdu = PDU.withFormat
    {
        uint8('typeOfPacket')
        uint16('requestIDValue')
        uint32('destinationAddress')
        uint32('sourceAddress')
    }

    // PDU for DATA and ACK packets.
    private final static PDU dataMsg = PDU.withFormat
    {
        uint8('source')
        uint8('destination')
        uint16('dataPktId')
    }

    private AgentID phy, node, rtr, mac

    private int myAddr

    private final static int RREQ = 0x01
    private final static int RREP = 0x02
    private final static int RIDZ = 0

    private final static int MAX_TRANSMISSIONS = 4  // 1 + re-transmissions

    ArrayList<Rodi.RoutingInformation> myroutingTable  = new ArrayList<Rodi.RoutingInformation>()   //routing table
    ArrayList<Rodi.PacketHistory> mypacketHistoryTable = new ArrayList<Rodi.PacketHistory>()        //packet history table
    ArrayList<Rodi.Attempt> attempting                 = new ArrayList<Rodi.Attempt>()              //re-tx attempts
    ArrayList<Rodi.TxReserve> reservationTable         = new ArrayList<Rodi.TxReserve>()            //packets for MAC

    private final static int ROUTING_PROTOCOL   = Protocol.ROUTING
    private final static int DATA_PROTOCOL      = Protocol.DATA
    private final static int ACK_PROTOCOL       = Protocol.USER

    private int temp = 0    // Initial request ID of a node. Incremented with every route discovery request.

    @Override
    void setup()
    {
        register(Services.ROUTE_MAINTENANCE)
    }

    @Override
    void startup()
    {
        node   = agentForService(Services.NODE_INFO)
        myAddr = node.Address
        mac    = agentForService(Services.MAC)
        rtr    = agentForService(Services.ROUTING)
        phy    = agentForService(Services.PHYSICAL)

        subscribe phy
    }

    // Broadcast an RREQ packet.
    private void sendRreqBroadcast(int destination)
    {
        PacketHistory pd = new PacketHistory(requestIDNo: temp, sendernode: myAddr)
        mypacketHistoryTable.add(pd)        // Adding this packet in my PH table.

        // Preparing the RREQ packet for broadcast.
        def bytes = pdu.encode(typeOfPacket: RREQ, requestIDValue: temp, destinationAddress: destination, sourceAddress: myAddr)
        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
        sendMessage(tx)
    }

    // Receive the notification within one round trip time, otherwise send a new RDR.
    /*
    private void checkNotification(int dest)
    {
        add new WakerBehavior(2*networksize*hoptime + 3000, 
        {
            // RT is still empty, that means I never got the notification. Do a new RD.
            if (myroutingTable.size() == 0)
            {
                println("Still no entry in the RT")
                def rdp = agentForService(Services.ROUTE_MAINTENANCE)
                rdp << new RouteDiscoveryReq(to: dest, maxHops: 50, count: 1)
                return
            }

            // RT does have some entries. Let's see whether we got the intended destination or not.
            else
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    // The destination is there in the RT.
                    if (myroutingTable.get(i).destinationAddress == dest)
                    {
                        return
                    }
                }

                // This means the destination couldn't be found. Start a route discovery all over again.
                println("The destination couldn't be found in the RT, initiating a new RD")
                def rdp = agentForService(Services.ROUTE_MAINTENANCE)
                rdp << new RouteDiscoveryReq(to: dest, maxHops: 50, count: 1)
                return
            }
            })
    }
    */

    // Sending Reservation Requests to MAC protocol.
    private void sendMessage(TxFrameReq txReq)
    {
        if (txReq.type == Physical.CONTROL)    // CTRL packets.
        {
            if (txReq.protocol == ROUTING_PROTOCOL)
            {
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: controlMsgDuration/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs       // send ReservationRequest.
            }

            if (txReq.protocol == ACK_PROTOCOL)
            {   // duration for ACK packets with a 2400 bps rate.
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: 13.5/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs       // send ReservationRequest.
            }
        }

        if (txReq.type == Physical.DATA)   // DATA packets.
        {
            ReservationReq rs = new ReservationReq(to: txReq.to, duration: dataMsgDuration/1000)
            TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
            reservationTable.add(tr)
            mac << rs       // send ReservationRequest.
        }
    }

    /*  0: Max no. of transmissions not yet reached, but the node has been searched before.
    *   1: Node is searching for the first time. Do a route discovery.
    *   2: Maximum number of transmissions reached for this node. Refuse the request.
    */
    private int retransmissionCheck(int destination)
    {
        for (int i = 0; i < attempting.size(); i++)
        {
            if (attempting.get(i).des == destination)
            {
                int transmissions = attempting.get(i).num       // Number of transmissions for this destination.
                if (transmissions == MAX_TRANSMISSIONS)         // Max number of Tx reached.
                {
                    return 2
                }
                else if (transmissions < MAX_TRANSMISSIONS)     // Max number of Tx not over.
                {
                    return 0
                }
            }
        }
        // It's either the first ever search by this node or this particular destination was never searched before.
        return 1
    }
    
    @Override
    Message processRequest(Message msg)
    {
        if (msg instanceof RouteDiscoveryReq)
        {
            int fd = msg.to     // Final Destination.

            // 1) RT is empty.
            if (myroutingTable.size == 0)
            {
                int retxcheck = retransmissionCheck(fd)     // Number of transmissions for this node.

                // This destination has not reached its maximum limit yet. Node is not busy in some other route discovery.
                if (retxcheck == 0)
                {
                    for (int i = 0; i < attempting.size(); i++)
                    {
                        if (attempting.get(i).des == fd)
                        {
                            attempting.get(i).num++     // Increment the number of retransmissions for this destination.
                            temp++                      // Increment temp (request ID number).
                            sendRreqBroadcast(fd)
                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }
                // When this destination is being searched for the first ever time.
                if (retxcheck == 1)
                {
                    Attempt tr = new Attempt(des: fd, num: 1)
                    attempting.add(tr)                  // Adding this attempt in the attempting table.
                    temp++                              // Incrementing the request ID.
                    sendRreqBroadcast(fd)
                    return new Message(msg, Performative.AGREE)
                }
                // Maximum limit reached. The node is unreachable, so refuse.
                if (retxcheck == 2)
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }

            /*  If my RT has some entries, check for destination first:
            *   1) If found, send a notification;
            *   2) If not, do a Route discovery if the number of re-transmissions permits.
            */
            else
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == fd) 
                    {
                        int biz = myroutingTable.get(i).nh         // Next hop for the destination.
                        rtr << new RouteDiscoveryNtf(to: fd, nextHop: biz, reliability: true)   // Sending the Route discovery notification.
                        return new Message(msg, Performative.AGREE)
                    }
                }
                // Destination was not found in the RT. Check the number of transmissions for this node.
                int retxcheck = retransmissionCheck(fd)

                // This destination has not reached its maximum limit yet. Node is not busy in some other route discovery.
                if (retxcheck == 0)
                {
                    for (int i = 0; i < attempting.size(); i++)
                    {
                        if (attempting.get(i).des == fd)
                        {
                            attempting.get(i).num++     // Incremented the number of retransmissions for this destination node.
                            temp++                      // Increase temp (request ID number), as a new route discovery is gonna start.
                            sendRreqBroadcast(fd)
                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }
                // When this destination is being searched for the first ever time.
                if (retxcheck == 1)
                {
                    Attempt tr = new Attempt(des: fd, num: 1)
                    attempting.add(tr)              // Adding this attempt in the attempting table.
                    temp++                          // Incrementing the request ID number.
                    sendRreqBroadcast(fd)
                    return new Message(msg, Performative.AGREE)
                }
                // Maximum limit reached. The node is unreachable, so refuse.
                if (retxcheck == 2)
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }
        }
        return null
    }

    @Override
    void processMessage(Message msg)
    {
        if (msg instanceof ReservationStatusNtf && msg.status == ReservationStatus.START)
        {
            for (int i = 0; i < reservationTable.size(); i++)
            {
                if (msg.requestID == reservationTable.get(i).resreq.requestID)
                {
                    phy << reservationTable.get(i).txreq
                    reservationTable.remove(i)
                }
            }
        }

        if (msg instanceof ReservationStatusNtf && msg.status == ReservationStatus.FAILURE)
        {
            for (int i = 0; i < reservationTable.size(); i++)
            {
                if (msg.requestID == reservationTable.get(i).resreq.requestID)
                {
                    reservationTable.remove(i)
                }
            }
        }
        // The ROUTE has not returned ACK within NET_TRAVERSAL_TIME.
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == false)
        {
            // Delete the route for msg.to Destination.
            for (int i = 0; i < myroutingTable.size(); i++) {
                if (myroutingTable.get(i).destinationAddress == msg.to) {
                    myroutingTable.remove(i)
                }
            }
            //  Do another Route Discovery.
            def rdp = agentForService(Services.ROUTE_MAINTENANCE)
            rdp << new RouteDiscoveryReq(to: msg.to, maxHops: 50, count: 1)
        }

        // DATA Packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.DATA && msg.protocol == DATA_PROTOCOL)
        {
            def recdata = dataMsg.decode(msg.data)
            int os = recdata.source
            int od = recdata.destination
            int da = recdata.dataPktId

            if (myAddr == od)       // I am the final destination.
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == os)
                    {
                        int lo = myroutingTable.get(i).nh       // Next hop.

                        // Preparing the ACK packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: lo, type: Physical.CONTROL, protocol: ACK_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }
            }

            else                    // I am not the final destination for the DATA packet.
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == od)
                    {
                        int go = myroutingTable.get(i).nh       // Next hop.

                        // Preparing the DATA packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: go, type: Physical.DATA, protocol: DATA_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }
            }
        }

        // ACK Packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.CONTROL && msg.protocol == ACK_PROTOCOL)
        {
            def recdata = dataMsg.decode(msg.data)
            int os = recdata.source
            int od = recdata.destination
            int da = recdata.dataPktId

            if (myAddr != os)                    // 2) I am not the OS of the DATA packet.
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == os)
                    {
                        int vo = myroutingTable.get(i).nh       // Next hop.

                        // Preparing the ACK packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: vo, type: Physical.CONTROL, protocol: ACK_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }
            }
        }

        // RREQ and RREP packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.CONTROL && msg.protocol == ROUTING_PROTOCOL)
        {
            def receivedData        = pdu.decode(msg.data)

            int packetType          = receivedData.typeOfPacket
            int requestIDNumber     = receivedData.requestIDValue
            int originalSource      = receivedData.sourceAddress
            int originalDestination = receivedData.destinationAddress

            if (packetType == RREQ)         // RREQ packet.
            {
                // 1) First ever packet I am receiving.
                if (mypacketHistoryTable.size() == 0)
                {
                    PacketHistory ph = new PacketHistory(requestIDNo: requestIDNumber, sendernode: originalSource)
                    mypacketHistoryTable.add(ph)            // Add the packet details.

                    RoutingInformation rt = new RoutingInformation(destinationAddress: originalSource, nh: msg.from)
                    myroutingTable.add(rt)                  // Add the routing details for Backward Pointer.

                    if (originalSource != msg.from)         // msg.from is an Intermediate node.    
                    {
                        RoutingInformation rttwo = new RoutingInformation(destinationAddress: msg.from, nh: msg.from)
                        myroutingTable.add(rttwo)       // Also, add the details of the intermediate node.     
                    }

                    // 1.1) If the packet was for me only.
                    if(myAddr == originalDestination)
                    {
                        // Preparing an RREP back to the sender node.
                        def bytes = pdu.encode(typeOfPacket: RREP, requestIDValue: RIDZ, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: msg.from, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                    // 1.2) I am not the final destination.
                    else
                    {   
                        // Preparing an RREQ packet for re-broadcast.
                        def bytes = pdu.encode(typeOfPacket: packetType, requestIDValue: requestIDNumber, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }

                // 2) This is not the first RREQ packet I'm receiving.
                else
                {   
                    //For a particular broadcast, a node should recieve only one packet with a particular request ID and a particular node.
                    for(int i = 0; i < mypacketHistoryTable.size(); i++)
                    {
                        if(mypacketHistoryTable.get(i).requestIDNo == requestIDNumber && mypacketHistoryTable.get(i).sendernode == originalSource)
                        {
                            return
                        }
                    }

                    PacketHistory ph = new PacketHistory(requestIDNo: requestIDNumber, sendernode: originalSource)
                    mypacketHistoryTable.add(ph)        // This is a new packet. Add its details in the PH table.

                    int flag = 0
                    for (int i = 0; i < myroutingTable.size(); i++)
                    {
                        if (myroutingTable.get(i).destinationAddress == originalSource) // The OS is already there in my RT.
                        {
                            flag = 1        // Raise the flag in case this node was already there in the RT.

                            myroutingTable.get(i).nh = msg.from     // Updated next hop.
                            break
                        }
                    }

                    if (flag == 0)      // If I never had the OS in my RT.
                    {
                        RoutingInformation rt = new RoutingInformation(destinationAddress: originalSource, nh: msg.from)
                        myroutingTable.add(rt)          // Routing details of the OS.
                    }

                    if (originalSource != msg.from)     // msg.from is an Intermediate node.
                    {
                        int verify = 0
                        for (int i = 0; i < myroutingTable.size(); i++)
                        {
                            if (myroutingTable.get(i).destinationAddress == msg.from)
                            {
                                verify = 1      // verify = 1 means this node was already there in the RT.

                                myroutingTable.get(i).nh = msg.from         // It becomes a neighbour node.
                                break
                            }
                        }

                        if (verify == 0)        // If this node was never there in the RT.
                        {
                            RoutingInformation rttwo = new RoutingInformation(destinationAddress: msg.from, nh: msg.from)
                            myroutingTable.add(rttwo)       // Add the neighbour node's details.
                        }
                    }

                    // 2.1) I am the final destination.
                    if (myAddr == originalDestination)
                    {
                        // Preparing an RREP back to the OS.
                        def bytes = pdu.encode(typeOfPacket: RREP, requestIDValue: RIDZ, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: msg.from, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }

                    // 2.2) I am not the final destination. I check my RT.
                    else
                    {
                        for (int i = 0; i < myroutingTable.size(); i++)
                        {
                            if (myroutingTable.get(i).destinationAddress == originalDestination)
                            {
                                // Preparing an RREP back to the OS.
                                def bytes = pdu.encode(typeOfPacket: RREP, requestIDValue: RIDZ, destinationAddress: originalDestination, sourceAddress: originalSource)
                                TxFrameReq tx = new TxFrameReq(to: msg.from, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                                sendMessage(tx)
                                return
                            }
                        }

                        // The RT does not have the final destination entry, re-broadcast the RREQ packet.
                        def bytes = pdu.encode(typeOfPacket: packetType, requestIDValue: requestIDNumber, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }
            } // RREQ

            // RREP packets.
            if (packetType == RREP)
            {   
                int ver = 0
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == originalDestination)
                    {
                        ver = 1         // Ver = 1 means this destination is already there in the RT.

                        myroutingTable.get(i).nh = msg.from     // Updated next hop.
                        break
                    }
                }

                if (ver == 0)       // If the FD node was never there in the RT.
                {
                    RoutingInformation routingEntryForward = new RoutingInformation(destinationAddress: originalDestination, nh: msg.from)
                    myroutingTable.add(routingEntryForward)
                }

                if (originalDestination != msg.from)    // msg.from is an Intermediate node.
                {
                    int veri = 0
                    for (int i = 0; i < myroutingTable.size(); i++)
                    {
                        if (myroutingTable.get(i).destinationAddress == msg.from)
                        {
                            veri = 1        // Veri = 1 means this node is already there in the RT.

                            myroutingTable.get(i).nh = msg.from     // Updated next hop.
                            break
                        }
                    }

                    if (veri == 0)          // This node was never there in the RT, add its details.
                    {
                        RoutingInformation immediateneighbour = new RoutingInformation(destinationAddress: msg.from, nh: msg.from)
                        myroutingTable.add(immediateneighbour)
                    }
                }

                // 1) I am the OS.
                if (myAddr == originalSource)
                {
                    rtr << new RouteDiscoveryNtf(to: originalDestination, nextHop: msg.from, reliability: true) // Sending RouteDiscoveryNtf.
                    return
                }

                // 2) I am not the OS.
                else
                {
                    for(int p = 0; p < myroutingTable.size(); p++)
                    {
                        if (myroutingTable.get(p).destinationAddress == originalSource)
                        {
                            int hop = myroutingTable.get(p).nh

                            // Preparing the RREP packet to be sent back to the OS.
                            def bytes = pdu.encode(typeOfPacket: packetType, requestIDValue: requestIDNumber, destinationAddress: originalDestination, sourceAddress: originalSource)
                            TxFrameReq tx = new TxFrameReq(to: hop, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                            sendMessage(tx)
                            return
                        }
                    }
                }
            } // RREP
        }
    }

    // Parameters to be received from the simulation file.
    int networksize
    int controlMsgDuration
    int dataMsgDuration

}
