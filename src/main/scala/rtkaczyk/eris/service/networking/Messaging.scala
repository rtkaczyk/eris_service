package rtkaczyk.eris.service.networking

import rtkaczyk.eris.api.Common
import java.io.InputStream
import java.io.OutputStream
import scala.annotation.tailrec
import Exceptions._

trait Messaging extends Common {
  
  def in: InputStream
  def out: OutputStream
  
  protected def writeLen(n: Int) {
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
  
  protected def readLen(): Int = {
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


  protected def readNBytes(N: Int) = {
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