package rtkaczyk.eris.service.networking

import java.io.InputStream
import rtkaczyk.eris.api.Common
import rtkaczyk.eris.service.networking.Exceptions.ConnectionError

class TOInputStream(val in: InputStream, val timeout: Long) extends InputStream with Common {
  
  override def available = in.available
  override def close = in.close
  override def mark(readlimit: Int) = in.mark(readlimit)
  override def markSupported = in.markSupported
  override def reset = in.reset
  override def skip(n: Long) = in.skip(n)
  
  def read: Int = {
    waitForBytes
    in.read
  }

  override def read(buffer: Array[Byte]) = {
    waitForBytes
    in.read(buffer)
  }
  
  override def read(buffer: Array[Byte], offset: Int, length: Int) = {
    waitForBytes
    in.read(buffer, offset, length)
  }
  
  private def waitForBytes {
    val t = now
    while (available <= 0) {
      if (now - t > timeout) 
        throw new ConnectionError("Connection timed out")
      Thread sleep 50L
    }
  }
}