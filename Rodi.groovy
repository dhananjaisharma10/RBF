import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.*

/**
 * The class implements the Routing Protocol by Rahman et al.
 *
 * Reference:
 * R. H. Rahman, C. Benson, and M. Frater, "Routing protocols for underwater ad hoc networks," IEEE Oceans, 2012.
 */

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

    private class FatalAttempts
    {
        private int dest
        private int attempt
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

    private final static int MAX_TRANSMISSIONS  = 5     // 1 + re-transmissions
    private final static int MAX_FATAL_ATTEMPTS = 2     // If the ACK reception fails twice for a route, delete it

    ArrayList<Rodi.RoutingInformation> myroutingTable  = new ArrayList<Rodi.RoutingInformation>()   //routing table
    ArrayList<Rodi.PacketHistory> mypacketHistoryTable = new ArrayList<Rodi.PacketHistory>()        //packet history table
    ArrayList<Rodi.Attempt> attempting                 = new ArrayList<Rodi.Attempt>()              //re-tx attempts
    ArrayList<Rodi.TxReserve> reservationTable         = new ArrayList<Rodi.TxReserve>()            //packets for MAC
    ArrayList<Rodi.FatalAttempts> fatalAttemptTable    = new ArrayList<Rodi.FatalAttempts>()        //fatal ACK-reception history

    private final static int ROUTING_PROTOCOL   = Protocol.ROUTING      // To identify RREQ and RREP packets.
    private final static int DATA_PROTOCOL      = Protocol.DATA         // To identify DATA packets.
    private final static int ACK_PROTOCOL       = Protocol.USER         // To identify ACK packets.

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
        mypacketHistoryTable.add(pd)        // Adding this packet in the Packet History (PH) table.

        // Preparing the RREQ packet for broadcast.
        def bytes = pdu.encode(typeOfPacket: RREQ, requestIDValue: temp, destinationAddress: destination, sourceAddress: myAddr)
        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
        sendMessage(tx)
    }

    // When a RouteDiscoveryNtf is received for a DESTINATION with FALSE reliability.
    // The route is DELETED.
    private void routeMaintenance(int destinationNode)
    {
        // No history in FATAL ATTEMPTS, add this incident.
        if (fatalAttemptTable.size() == 0)
        {
            FatalAttempts fa = new FatalAttempts(dest: destinationNode, attempt: 1)
            fatalAttemptTable.add(fa)
        }
        // There are some fatal attempts.
        else
        {
            for (int i = 0; i < fatalAttemptTable.size(); i++)
            {
                // Check for FATAL ATTEMPT history of the destinationNode.
                if (fatalAttemptTable.get(i).dest == destinationNode)
                {
                    // MAX_FATAL_ATTEMPTS not done.
                    if (fatalAttemptTable.get(i).attempt < MAX_FATAL_ATTEMPTS)
                    {
                        fatalAttemptTable.get(i).attempt++
                    }
                    // MAX_FATAL_ATTEMPTS reached.
                    if (fatalAttemptTable.get(i).attempt == MAX_FATAL_ATTEMPTS)
                    {
                        fatalAttemptTable.remove(i) // Remove history of fatal attemps for this destinationNode.
                        for (int j = 0; j < myroutingTable.size(); j++)
                        {
                            if (myroutingTable.get(j).destinationAddress == destinationNode)
                            {
                                myroutingTable.remove(j)    // Remove this NODE entry from the RT.
                                break
                            }
                        }
                        // If needed, the node will send a RouteDiscoveryReq for this destinationNode.
                    }
                    return
                }
            }

            // If this node was never there in the FATAL ATTEMPT history, add it.
            FatalAttempts fa = new FatalAttempts(dest: destinationNode, attempt: 1)
            fatalAttemptTable.add(fa)
        }
    }

    // Sending Reservation Requests to MAC protocol.
    private void sendMessage(TxFrameReq txReq)
    {
        if (txReq.type == Physical.CONTROL)         // CONTROL packets.
        {
            if (txReq.protocol == ROUTING_PROTOCOL) // RREQ or RREP packet.
            {
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: controlMsgDuration/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs                           // send ReservationRequest.
            }

            if (txReq.protocol == ACK_PROTOCOL)     // ACK packets.
            {
                // Duration for ACK packets with a 2400 bps rate: 13.5 milliseconds.
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: 13.5/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs                           // send ReservationRequest.
            }
        }

        if (txReq.type == Physical.DATA)            // DATA packets.
        {
            ReservationReq rs = new ReservationReq(to: txReq.to, duration: dataMsgDuration/1000)
            TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
            reservationTable.add(tr)
            mac << rs                               // send ReservationRequest.
        }
    }

   /*   0: The node has been searched before, but there are RE-TRANSMISSIONS left for it.
    *   1: Node is being searched for the first time, do a route discovery.
    *   2: No more RE-TRANSMISSIONS allowed. Refuse the route discovery request.
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
    
    // Any Request is received here.
    @Override
    Message processRequest(Message msg)
    {
        if (msg instanceof RouteDiscoveryReq)       // ROUTING agent received a Route Discovery Request.
        {
            int fd = msg.to                         // Final Destination.

            // 1) Routing Table (RT) is empty.
            if (myroutingTable.size == 0)
            {
                int retxcheck = retransmissionCheck(fd)     // Number of transmissions for this node.

                // The node has been searched before, but there are RE-TRANSMISSIONS left for it.
                if (retxcheck == 0)
                {
                    for (int i = 0; i < attempting.size(); i++)
                    {
                        if (attempting.get(i).des == fd)
                        {
                            attempting.get(i).num++     // Increment the number of transmissions for this destination.
                            temp++                      // Increment temp (request ID number).
                            sendRreqBroadcast(fd)       // Send a RREQ BROADCAST.

                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }
                
                // Node is being searched for the first time, do a route discovery.
                if (retxcheck == 1)
                {
                    Attempt tr = new Attempt(des: fd, num: 1)
                    attempting.add(tr)                  // Adding this attempt in the attempting table.
                    temp++                              // Incrementing the request ID.
                    sendRreqBroadcast(fd)               // Send a RREQ BROADCAST.

                    return new Message(msg, Performative.AGREE)
                }

                // No more RE-TRANSMISSIONS allowed. Refuse the route discovery request.
                if (retxcheck == 2)
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }

           /*   2) RT is not empty.
            *   If my RT has some entries, check for destination first:
            *   1) If found, send a Route Discovery notification with TRUE reliability;
            *   2) If not, do a Route discovery if the number of re-transmissions permits.
            */
            else
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == fd) 
                    {
                        int nextnode = myroutingTable.get(i).nh         // Next hop for the destination.

                        // Sending the Route discovery notification.
                        rtr << new RouteDiscoveryNtf(to: fd, nextHop: nextnode, reliability: true)

                        return new Message(msg, Performative.AGREE)
                    }
                }

                // Destination was not found in the RT. Check the number of transmissions for this node.
                int retxcheck = retransmissionCheck(fd)

                // The node has been searched before, but there are RE-TRANSMISSIONS left for it.
                if (retxcheck == 0)
                {
                    for (int i = 0; i < attempting.size(); i++)
                    {
                        if (attempting.get(i).des == fd)
                        {
                            attempting.get(i).num++     // Incremented the number of retransmissions for this destination node.
                            temp++                      // Increase temp (request ID number), as a new route discovery is gonna start.
                            sendRreqBroadcast(fd)       // Send a RREQ BROADCAST.

                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }

                // Node is being searched for the first time, do a route discovery.
                if (retxcheck == 1)
                {
                    Attempt tr = new Attempt(des: fd, num: 1)
                    attempting.add(tr)              // Adding this attempt in the attempting table.
                    temp++                          // Incrementing the request ID number.
                    sendRreqBroadcast(fd)           // Send a RREQ BROADCAST.

                    return new Message(msg, Performative.AGREE)
                }

                // No more RE-TRANSMISSIONS allowed. Refuse the route discovery request.
                if (retxcheck == 2)
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }
        }
        return null
    }

    // Any Notification will be received here.
    @Override
    void processMessage(Message msg)
    {
        // Reservation Status Notification is received from the MAC service.
        // Using requestID of the notification, the corresponding Reservation Request is extracted from the Reservation Table.
        if (msg instanceof ReservationStatusNtf)
        {
            // START sending the packet.
            if (msg.status == ReservationStatus.START)
            {
                for (int i = 0; i < reservationTable.size(); i++)
                {
                    if (msg.requestID == reservationTable.get(i).resreq.requestID)
                    {
                        // Send this message to PHYSICAL channel.
                        phy << reservationTable.get(i).txreq

                        // Removing this entry from the Reservation table.
                        reservationTable.remove(i)
                        break
                    }
                }
            }

            // FAILED to find a reservation for the packet. Remove it.
            if (msg.status == ReservationStatus.FAILURE)
            {
                for (int i = 0; i < reservationTable.size(); i++)
                {
                    if (msg.requestID == reservationTable.get(i).resreq.requestID)
                    {
                        // Removing this entry from the Reservation table.
                        reservationTable.remove(i)
                        break
                    }
                }
            }
        }

        // The ROUTE has not returned ACK within NET_TRAVERSAL_TIME.
        if (msg instanceof RouteDiscoveryNtf && msg.reliability == false)
        {
            // The route shall be DELETED for this DESTINATION.
            routeMaintenance(msg.to)
        }

        // DATA Packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.DATA && msg.protocol == DATA_PROTOCOL)
        {
            def recdata = dataMsg.decode(msg.data)      // DECODING the packet data.

            int os = recdata.source                     // Original Source.
            int od = recdata.destination                // Original Destination.
            int da = recdata.dataPktId                  // Packet ID.

            if (myAddr == od)       // 1) The final destination for the DATA packet.
            {
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == os)
                    {
                        int lo = myroutingTable.get(i).nh       // Next hop, or msg.from

                        // Preparing the ACK packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: lo, type: Physical.CONTROL, protocol: ACK_PROTOCOL, data: bytes)
                        sendMessage(tx)
                        break
                    }
                }
            }

            else                    // 2) Not the final destination for the DATA packet.
            {
                // Searching for the next hop to the Final Destination.
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == od)
                    {
                        int go = myroutingTable.get(i).nh       // Next hop.

                        // Preparing the DATA packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: go, type: Physical.DATA, protocol: DATA_PROTOCOL, data: bytes)
                        sendMessage(tx)
                        break
                    }
                }
            }
        }

        // ACK Packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.CONTROL && msg.protocol == ACK_PROTOCOL)
        {
            def recdata = dataMsg.decode(msg.data)  // DECODING the packet data.

            int os = recdata.source                 // Original Source.
            int od = recdata.destination            // Original Destination.
            int da = recdata.dataPktId              // Packet ID.

            if (myAddr != os)                       // Not the OS of the DATA packet.
            {
                // Searching for the next hop to the Original Source of the DATA packet.
                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    if (myroutingTable.get(i).destinationAddress == os)
                    {
                        int vo = myroutingTable.get(i).nh       // Next hop.

                        // Preparing the ACK packet.
                        def bytes = dataMsg.encode([source: os, destination: od, dataPktId: da])
                        TxFrameReq tx = new TxFrameReq(to: vo, type: Physical.CONTROL, protocol: ACK_PROTOCOL, data: bytes)
                        sendMessage(tx)
                        break
                    }
                }
            }
        }

        // Received either an RREQ or RREP packet.
        if (msg instanceof RxFrameNtf && msg.type == Physical.CONTROL && msg.protocol == ROUTING_PROTOCOL)
        {
            def receivedData        = pdu.decode(msg.data)              // DECODING the packet information.

            int packetType          = receivedData.typeOfPacket         // Packet type: RREQ or RREP.
            int requestIDNumber     = receivedData.requestIDValue       // Request ID for the RREQ packet.
            int originalSource      = receivedData.sourceAddress        // Original Source.
            int originalDestination = receivedData.destinationAddress   // Original Destination.

            if (packetType == RREQ)         // RREQ packet.
            {
                // 1) First ever packet for this node.
                if (mypacketHistoryTable.size() == 0)
                {
                    PacketHistory ph = new PacketHistory(requestIDNo: requestIDNumber, sendernode: originalSource)
                    mypacketHistoryTable.add(ph)            // Add the packet details.

                    RoutingInformation rt = new RoutingInformation(destinationAddress: originalSource, nh: msg.from)
                    myroutingTable.add(rt)                  // Add the routing details for BACKWARD POINTER.

                    // In case the RREQ was not received directly from the OS.
                    // Add the details of the intermediate neighbour node in the RT.
                    if (originalSource != msg.from)  
                    {
                        RoutingInformation rttwo = new RoutingInformation(destinationAddress: msg.from, nh: msg.from)
                        myroutingTable.add(rttwo)   
                    }

                    // 1.1) The FINAL DESTINATION.
                    if (myAddr == originalDestination)
                    {
                        // Preparing an RREP back to the Origianl Source.
                        def bytes = pdu.encode(typeOfPacket: RREP, requestIDValue: RIDZ, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: msg.from, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                    // 1.2) NOT the FINAL DESTINATION.
                    else
                    {   
                        // Preparing an RREQ packet for re-broadcast.
                        def bytes = pdu.encode(typeOfPacket: packetType, requestIDValue: requestIDNumber, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                    }
                }

                // 2) Not the first RREQ packet received by this node.
                else
                {   
                    // For a particular broadcast, a node should recieve only one packet with a particular request ID and a particular node.
                    // Doing a REDUNDANCY check for this PACKET.
                    for(int i = 0; i < mypacketHistoryTable.size(); i++)
                    {
                        if(mypacketHistoryTable.get(i).requestIDNo == requestIDNumber && mypacketHistoryTable.get(i).sendernode == originalSource)
                        {
                            return          // This packet is already there in the PH table. Refuse it.
                        }
                    }

                    // This is a new packet. Add its details in the PH table.
                    PacketHistory ph = new PacketHistory(requestIDNo: requestIDNumber, sendernode: originalSource)
                    mypacketHistoryTable.add(ph)

                    // Checking the RT for the Original SOURCE.
                    int flag = 0

                    for (int i = 0; i < myroutingTable.size(); i++)
                    {
                        // The OS is already there in my RT.
                        if (myroutingTable.get(i).destinationAddress == originalSource)
                        {
                            flag = 1        // Raise the flag in case the OS is already there in the RT.

                            myroutingTable.get(i).nh = msg.from     // Updating next hop.
                            break
                        }
                    }

                    if (flag == 0)      // If the OS was never there in the RT, add its details.
                    {
                        RoutingInformation rt = new RoutingInformation(destinationAddress: originalSource, nh: msg.from)
                        myroutingTable.add(rt)          // Routing details of the OS.
                    }

                    // In case the RREQ was not received directly from the OS.
                    // Add the details of the intermediate neighbour node in the RT.
                    if (originalSource != msg.from)
                    {
                        // Check the RT for this intermediate neighbour node.
                        int verify = 0

                        for (int i = 0; i < myroutingTable.size(); i++)
                        {
                            // This intermediate neighbour node is already there in the RT.
                            if (myroutingTable.get(i).destinationAddress == msg.from)
                            {
                                verify = 1      // Verify = 1 means this node is already there in the RT.

                                // The next hop becomes same as that of the destination, which implies it's a neighbour node.
                                myroutingTable.get(i).nh = msg.from
                                break
                            }
                        }

                        if (verify == 0)        // If this node was never there in the RT.
                        {
                            RoutingInformation rttwo = new RoutingInformation(destinationAddress: msg.from, nh: msg.from)
                            myroutingTable.add(rttwo)       // Add the neighbour node's details.
                        }
                    }

                    // 2.1) Final Destination.
                    if (myAddr == originalDestination)
                    {
                        // Preparing an RREP back to the OS.
                        def bytes = pdu.encode(typeOfPacket: RREP, requestIDValue: RIDZ, destinationAddress: originalDestination, sourceAddress: originalSource)
                        TxFrameReq tx = new TxFrameReq(to: msg.from, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                        sendMessage(tx)
                        return
                    }

                    // 2.2) NOT the final destination, check the RT for the FINAL DESTINATION.
                    else
                    {
                        for (int i = 0; i < myroutingTable.size(); i++)
                        {
                            // This node has the address to the FINAL DESTINATION.
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
                // Check for the Original Destination (OD) in the RT.
                int ver = 0

                for (int i = 0; i < myroutingTable.size(); i++)
                {
                    // The OD is there in the RT
                    if (myroutingTable.get(i).destinationAddress == originalDestination)
                    {
                        ver = 1         // Ver = 1 means this destination is already there in the RT.

                        myroutingTable.get(i).nh = msg.from     // Updated next hop.
                        break
                    }
                }

                if (ver == 0)       // If the OD node was never there in the RT, add its details.
                {
                    // FORWARD ROUTE entry.
                    RoutingInformation routingEntryForward = new RoutingInformation(destinationAddress: originalDestination, nh: msg.from)
                    myroutingTable.add(routingEntryForward)
                }

                // If the RREP was not received directly from the OD, add routing details of the intermediate negibhour node.
                if (originalDestination != msg.from)
                {
                    // Check for the intermediate neghbour node in the RT.
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

                // 1) The ORIGINAL SOURCE.
                if (myAddr == originalSource)
                {
                    // Sending RouteDiscoveryNtf.
                    rtr << new RouteDiscoveryNtf(to: originalDestination, nextHop: msg.from, reliability: true)
                }

                // 2) Not the OS.
                else
                {
                    // Searching for next hop towards the OS.
                    for(int p = 0; p < myroutingTable.size(); p++)
                    {
                        if (myroutingTable.get(p).destinationAddress == originalSource)
                        {
                            int hop = myroutingTable.get(p).nh      // Next hop towards the OS.

                            // Preparing the RREP packet to be sent back to the OS.
                            def bytes = pdu.encode(typeOfPacket: packetType, requestIDValue: requestIDNumber, destinationAddress: originalDestination, sourceAddress: originalSource)
                            TxFrameReq tx = new TxFrameReq(to: hop, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                            sendMessage(tx)
                            break
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