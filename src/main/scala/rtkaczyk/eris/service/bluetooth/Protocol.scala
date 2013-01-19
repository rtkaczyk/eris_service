package rtkaczyk.eris.service.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.IOException
import rtkaczyk.eris.api.Common
import android.util.Log
import rtkaczyk.eris.api.Packet
import rtkaczyk.eris.bluetooth.BtMessages.{Request, Response, Error}
import scala.collection.JavaConversions._
import scala.collection.mutable.Buffer
import scala.math.{min, max}
import scala.annotation.tailrec
import rtkaczyk.eris.api.DeviceId

class Protocol(socket: BluetoothSocket, full: Boolean) extends Common {

  private val in = socket.getInputStream
  private val out = socket.getOutputStream

  val deviceId = DeviceId(socket.getRemoteDevice).id
  
  private var all = 0
  def allPackets = all
  
  private var tr = (Long.MaxValue, 0L)
  private def updateTimerange(from: Long, to: Long): Unit = {
    val f = min(tr._1, from)
    val t = max(tr._2, to)
    tr = (f, t)
  }
  private def updateTimerange(t: Long): Unit = updateTimerange(t, t)
  def from = tr._1
  def to = tr._2
  
  def requestPackets(from: Long = 0, to: Long = 0, 
      limit: Int = 0, batch: Int = 0) {
    
    val request = prepareRequest(from, to, limit, batch).toByteArray
    Log.d(TAG, "Request is %d bytes long" format request.length)
    writeLen(request.length)
    out write request
  }
  
  def getPackets(): List[Packet] = {
    val n = readLen()
    Log.d(TAG, "Response is %d bytes long" format n)
    
    if (n > 0) {
      val response = Response parseFrom readNBytes(n)
      
      if (response.hasError)
        attendError(response.getError)
        
      if (response.hasNoPackets)
        all = response.getNoPackets
        
      if (!full)
        updateTimerange(response.getFrm, response.getTo)
      
      for {
        p <- response.getPacketsList.toList
        data = p.getData
        timestamp = if (p.hasTimestamp) p.getTimestamp else response.getTo 
      } yield {
        if (full)
          updateTimerange(timestamp)
        new Packet(deviceId, timestamp, data.toByteArray)
      }
    } 
    else {
      Nil
    }
  }
  
  def confirm: Boolean = {
    writeLen(allPackets)
    readLen > 0
  }
  
  private def attendError(err: Error) {
    err.getCode match {
      case Error.Code.CONNECTION_ERROR =>
        throw new ConnectionError(err.getDescription)
      case Error.Code.INTERNAL_ERROR =>
        throw new InternalError(err.getDescription)
      case Error.Code.INVALID_REQUEST =>
        throw new InvalidRequest(err.getDescription)
      case _ =>
        throw new ConnectionError("Unknown error")
    } 
  }
  
  private def prepareRequest(from: Long, to: Long, limit: Int, batch: Int): Request = {
    val builder = Request.newBuilder()
    if (from > 0) 
      builder setFrm from
    if (to > 0)
      builder setTo to
    if (limit > 0)
      builder setLimit limit
    if (batch > 0)
      builder setBatch batch
    if (!full)
      builder setFull full
      
    builder.build
  }
  
  private def writeLen(n: Int) {
    require(n >= 0)
    
    @tailrec
    def evalLen(bytes: List[Int], n: Int): Array[Byte] = {
      val byte = n & 0x7F
      val rest = n >> 7
      if (rest > 0)
        evalLen((byte | 0x80) :: bytes, rest)
      else 
        (byte :: bytes).reverse.toArray map { _.toByte }
    }
    
    out write evalLen(Nil, n) 
  }
  
  private def readLen(): Int = {
    @tailrec
    def getBytes(bytes: List[Int], s: Stream[Int]): List[Int] = {
      if (s.head == -1)
        throw new ConnectionError("Unexpected end of data stream")
      if ((s.head & 0x80) > 0) 
        getBytes(s.head :: bytes, s.tail)
      else {
        if (bytes.size >= 4)
          throw new InvalidResponse("Response too long")
        s.head :: bytes
      }
    }
    
    @tailrec
    def evalLen(bytes: List[Int], n: Int): Int = bytes match {
      case b :: bs => evalLen(bs, ((b & 0x7F) << (bs.size * 7)) | n)
      case Nil => n
    }
    
    evalLen(getBytes(Nil, Stream continually in.read), 0)
  }


  private def readNBytes(N: Int) = {
    //Log.w(TAG, "readNBytes, available: %d" format in.available)

    val bytes = new Array[Byte](N)
    
    @tailrec
    def read(n: Int): Int = {
      val rd = in.read(bytes, N - n, n)
      if (rd == -1)
        N - n
      else if (rd < n)
        read(n - rd)
      else
        N
    }
    
    val actual = read(N)
    if (actual < N)
      throw new ConnectionError("Unexpected end of data stream: " +
          "actual: %d, expected: %d" format (actual, N))

    bytes
  }
}