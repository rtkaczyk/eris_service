package rtkaczyk.eris.service.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import rtkaczyk.eris.api.Common
import java.io.IOException
import rtkaczyk.eris.api.DeviceId
import rtkaczyk.eris.api.Events._
import scala.util.control.Exception.ignoring
import rtkaczyk.eris.service.Msg
import scala.actors.Actor



trait Connection extends Actor {
  def inProgress = false
  def cancel {}
}

object Connection {
  private object FakeConnection extends Connection { def act {} }
  case class Request (
    from: Long = 0,
    to: Long = 0,
    limit: Int = 0,
    batch: Int = 0
  )
  
  def apply(): Connection = FakeConnection
  def apply(receiver: Receiver, device: BluetoothDevice, req: Request): Connection =
    new BtConnection(receiver, device, req)
}


class BtConnection(val Receiver: Receiver, device: BluetoothDevice, req: Connection.Request) 
extends Connection with Common {
  val Service = Receiver.Service
  val Storage = Receiver.Storage
  
  private var socket: BluetoothSocket = null
  private var noPackets = 0
  private var proto: Protocol = null
  private var done = false

  override def inProgress = !done
  
  def act {
    connect
  }
  
  override def cancel {
    close
  }
  
  private def connect {
    try {
      Log.i(TAG, "Connecting to [%s]" format DeviceId(device))
      val m = device.getClass getMethod ("createInsecureRfcommSocket", classOf[Int])
      socket = (m invoke (device, Integer valueOf Receiver.channel)).asInstanceOf[BluetoothSocket]
      if (inProgress) {
        socket.connect
        proto = new Protocol(socket, Receiver.full)
        
        Log.d(TAG, "Sending request")
        proto requestPackets(req.from, req.to, req.limit, req.batch)
        
        while (inProgress)
          proceed
      }
      else close
    }
    catch {
      case e: IOException if (e.getMessage == "Connection refused") => {
        Log.e(TAG, "Error while connecting to Bluetooth device [%s]:\n %s" 
            format (DeviceId(device), e.toString))
            Service !! ConnectionRefused(device)
            close
      }
      case e: Exception => {
        Log.e(TAG, "Error while connecting to Bluetooth device [%s]" 
            format DeviceId(device), e)
            onError(e.toString)
      }
    } 
    finally {
      Receiver ! Msg.ReceiverContinue
    }
  }
  
  
  private def proceed {
    Log.d(TAG, "Connection proceed")
    try {
      val packets = proto.getPackets
      if (!packets.isEmpty) {
        Log.d(TAG, "Received %d packets" format packets.size)
        noPackets += packets.size
        Storage ! Msg.StorePackets(packets)
        Service !! PacketsReceived(device, noPackets, proto.allPackets)
      } 
      else {
        val ack = proto.confirm
        if (ack)
          DeviceCache update (device, proto.to)
        else
          Log.w(TAG, "Server did not confirm correct number of packets")
        finish
      }
    }
    catch {
      case e: Exception =>
        Log.e(TAG, "Error while receiving from Bluetooth device [%s]" 
            format DeviceId(device), e)
        onError(e.toString)
    }
  }
  
  private def finish {
    close
    Log.i(TAG, "Connection to [%s] finished. Received %d packets" format 
        (DeviceId(device), noPackets))
    Service !! ConnectionFinished(device, noPackets, proto.from, proto.to)
  }
  
  private def onError(cause: String) {
    close
    Service !! ConnectionFailed(device, cause)
  }
  
  private def close {
    done = true
    ignoring(classOf[Exception]) {
      socket.close
    }
  }
}