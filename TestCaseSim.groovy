//! Simulation: Routing Protocol

import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.sim.*
import org.arl.unet.sim.channels.*
import static org.arl.unet.Services.*
import static org.arl.unet.phy.Physical.*

println '''
RBF Protocol Simulation
=======================
'''

///////////////////////////////////////////////////////////////////////////////
// modem and channel model parameters

modem.dataRate         = [2400, 2400].bps
modem.frameLength      = [11, 100].bytes
modem.preambleDuration = 0
modem.txDelay          = 0
modem.clockOffset      = 0.s
modem.headerLength     = 0.s

channel.model              = ProtocolChannelModel
channel.soundSpeed         = 1500.mps
channel.communicationRange = 100.m                     // Maximum TRANSMISSION Range
channel.interferenceRange  = 200.m                     // Twice that of the max tx range
channel.detectionRange     = 200.m                     // Twice that of the max tx range

///////////////////////////////////////////////////////////////////////////////
// simulation settings

def T = 15.minutes

def nodes = 1..5
def dimension = Math.cbrt(125000*nodes.size())

///////////////////////////////////////////////////////////////////////////////
// generate random network geometry
def nodeLocation = [:]
nodes.each { myAddr ->
  nodeLocation[myAddr] = [rnd(0.m, dimension.m), rnd(0.m, dimension.m), rnd(0.m, -dimension.m)]
}

// compute average distance between nodes for display
def sum = 0
def n = 0
def propagationDelay = new Integer[nodes.size()][nodes.size()]
nodes.each { n1 ->
  nodes.each { n2 ->
    if (n1 < n2) {
      n++
      sum += distance(nodeLocation[n1], nodeLocation[n2])
    }
    propagationDelay[n1-1][n2-1] = (int)(distance(nodeLocation[n1],nodeLocation[n2]) / channel.soundSpeed + 0.5)
  }
}

def avgRange = sum/n
println """Average internode distance: ${Math.round(avgRange)} m, delay: ${Math.round(1000*avgRange/channel.soundSpeed)} ms
TX Count\tRX Count\tLoss %\t\tOffered Load\tThroughput
--------\t--------\t------\t\t------------\t----------"""

File out = new File("logs/results.txt")
out.text = ''

//for (def load = loadRange[0]; load <= loadRange[1]; load += loadRange[2]) {
  load  = 1
  simulate T, {
    
    nodes.each { myAddr ->
    
      // Divide network load across nodes evenly.
      float loadPerNode = load/nodes.size() 
           
      def routingAgent = new Rodi()
      def macAgent = new Csma()
      
      if(myAddr == 1)
      {
        def myNode = node("${myAddr}", address: myAddr, location: nodeLocation[myAddr], shell: true, stack: {container ->   
          container.add 'rodi', routingAgent
          container.add 'mac', macAgent
          })
      }
      
      else
      {
        def myNode = node("${myAddr}", address: myAddr, location: nodeLocation[myAddr], stack: {container ->   
          container.add 'rodi', routingAgent
          container.add 'mac', macAgent
          })
      }
      macAgent.targetLoad         = loadPerNode
      macAgent.dataMsgDuration    = (int)(8000*modem.frameLength[1]/modem.dataRate[1] + 0.5)
      macAgent.controlMsgDuration = (int)(8000*modem.frameLength[0]/modem.dataRate[0] + 0.5)
      
      routingAgent.dataMsgDuration    = (int)(8000*modem.frameLength[1]/modem.dataRate[1] + 0.5)
      routingAgent.controlMsgDuration = (int)(8000*modem.frameLength[0]/modem.dataRate[0] + 0.5)
      routingAgent.networksize        = nodes.size()
      
      container.add 'load', new LoadGenerator(nodes-myAddr, loadPerNode)
      
    } // each
  
  } // simulation
  
  // display statistics
  float loss = trace.txCount ? 100*trace.dropCount/trace.txCount : 0
  println sprintf('%6d\t\t%6d\t\t%5.1f\t\t%7.3f\t\t%7.3f',
    [trace.txCount, trace.rxCount, loss, trace.offeredLoad, trace.throughput])

  // save to file
  out << "${trace.offeredLoad},${trace.throughput}\n"
  
//} // for
