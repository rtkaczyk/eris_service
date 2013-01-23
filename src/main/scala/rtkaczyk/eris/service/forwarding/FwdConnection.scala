package rtkaczyk.eris.service.forwarding

import rtkaczyk.eris.service.networking.Connection
import java.net.Socket
import android.util.Log
import rtkaczyk.eris.service.Msg
import rtkaczyk.eris.service.networking.Exceptions._
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.api.Events._
import scala.util.control.Exception.ignoring

class FwdConnection(val forwarder: Forwarder) extends Connection {
  val storage = forwarder.storage
  val service = forwarder.service
  
  var address = ""
  var port = 0
  
  private var done = false
  private var socket: Socket = null
  private var proto: Protocol = null
  private var batchN = 1
  private var sentPackets = 0
  private var allPackets = 0
  private var allBatches = 0
  
  override def inProgress = !done
  
  override def cancel = { 
    close
    this ! Msg.Kill
  }
  
  def act {
    storage ! Msg.CountPackets(this)
    loop {
      if (inProgress) {
        react {
          case Msg.PacketsCount(n) => {
            if (n > 0)
              connect
            else {
              forwarder ! Msg.ForwardingFinished(true)
              close
              exit
            } 
          }
          
          case Msg.ForwardingPrepared(n, count) => {
            allPackets = count
            allBatches = n
          }
          
          case Msg.PacketsToForward(packets) => {
            if (inProgress)
              proceed(packets)
          }
              
          case Msg.Kill => {
            close
            exit
          }
        }
      }
    }
  }
  
  private def connect {
    val address = forwarder.addresses find {
      case (addr, port) => 
        try {
          this.address = addr
          this.port = port
          
          socket = new Socket(addr, port)
          val connected = socket.isConnected
          if (!connected)
            throw new ConnectionError("Unknown error")
          
          Log.i(TAG, "Forwarding: connected to (%s, %d)" format (addr, port))
          connected
        } catch {
          case e: Exception => {
            Log.w(TAG, "Forwarding: connection refused for (%s, %d): %s" format (addr, port, e.toString))
            false
          }
        }
    }
    
    if (address.isDefined) {
      socket setSoTimeout forwarder.timeout
      proto = new Protocol(socket, forwarder.withTimestamp)
      service !! ForwardingStarted(this.address, port)
      
      storage ! Msg.PrepareForwarding
      storage ! Msg.SelectToForward(batchN)
      
    } else {
      Log.w(TAG, "Forwarding attempt failed")
      service !! ForwardingRefused
      forwarder ! Msg.ForwardingFinished(false)
      close
      exit
    }
  }
  
  private def proceed(packets: List[Packet]) {
    try {
      if (!packets.isEmpty) {
        batchN += 1
        storage ! Msg.SelectToForward(batchN)
      }
      if (proto.checkResponse) {
        finish()
      } else {
        proto sendPackets packets
        sentPackets += packets.size
        service !! PacketsForwarded(sentPackets, allPackets)
      }
      if (packets.isEmpty) {
        finish()
      }
    }
    catch {
      case e: Exception => {
        Log.e(TAG, "Error forwarding packets to (%s, %d)" format (address, port))
        finish(Some(e))
      }
    }
  }
  
  private def finish(exception: Option[Exception] = None) {
    var n = 0
    var ex: Option[Exception] = exception
    if (ex.isEmpty) {
      try {
        val c = proto.getConfirmation
        n = c._1
        ex = c._2
      } catch {
        case e: Exception => {
          Log.e(TAG, "Error forwarding packets to (%s, %d)" format (address, port))
          ex = Some(e)
        }
      }
    }
    storage ! Msg.DeleteForwarded(n)
    if (ex.isDefined)
      service !! ForwardingFailed(ex.get.toString)
    
    val k = n * forwarder.batchSize
    val confirmed = if (k > allPackets) allPackets else k
    service !! ForwardingFinished(confirmed, allPackets)
    forwarder ! Msg.ForwardingFinished(ex.isEmpty)

    close
    exit
  }
  
  private def close {
    done = true
    ignoring(classOf[Exception]) {
      socket.close
    }
  }
}