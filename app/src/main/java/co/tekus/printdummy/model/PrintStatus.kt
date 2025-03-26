package co.tekus.printdummy.model

sealed class PrintStatus {
    data class Success(val message: String) : PrintStatus()
    data class Error(val message: String) : PrintStatus()
}