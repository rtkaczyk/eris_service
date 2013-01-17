package rtkaczyk.eris.service.bluetooth

class ConnectionError(message: String) extends Exception(message)
class InvalidRequest(message: String) extends Exception(message)
class InvalidResponse(message: String) extends Exception(message)
class InternalError(message: String) extends Exception(message)