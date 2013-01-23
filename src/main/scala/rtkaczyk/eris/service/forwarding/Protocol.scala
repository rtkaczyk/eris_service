package rtkaczyk.eris.service.forwarding

import java.io.InputStream
import rtkaczyk.eris.service.networking.Messaging
import java.net.Socket
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.service.forwarding.FwdMessages.{Request, Response, Error, Packet => ProtoPacket}
import com.google.protobuf.ByteString
import rtkaczyk.eris.service.networking.Exceptions._
import android.util.Log

class Protocol(val socket: Socket, val withTimestamp: Boolean) extends Messaging {

  val in = socket.getInputStream
  val out = socket.getOutputStream
  
  def sendPackets(packets: List[Packet]) {
    if (packets.isEmpty) {
      writeLen(0)
    } else {
      val request = prepareRequest(packets).toByteArray
      Log.d(TAG, "Request is %d bytes long" format request.length)
      writeLen(request.length)
      out write request
    }
  }
  
  def getConfirmation: (Int, Option[Exception]) = {
    val n = readLen
    val m = readLen
    (n, getError(m))
  } 
  
  def checkResponse = in.available > 0
  
  private def prepareRequest(packets: List[Packet]) = {
    val builder = Request.newBuilder
    packets foreach { p =>
      val pBuilder = ProtoPacket.newBuilder
      pBuilder setDevice p.device
      pBuilder setData (ByteString copyFrom p.data)
      if (withTimestamp)
        pBuilder setTimestamp p.time
      
      builder addPackets pBuilder
    }
    
    builder.build
  }
  
  private def getError(n: Int): Option[Exception] = {
    if (n > 0) {
      val response = Response parseFrom readNBytes(n)
      val err = response.getError
      Some( err.getCode match {
        case Error.Code.INVALID_REQUEST =>
          new InvalidRequest(err.getDescription)
        case Error.Code.INTERNAL_ERROR =>
          new InternalError(err.getDescription)
        case _ =>
          new ConnectionError(err.getDescription)
      })
    }
    else 
      None
  }

}