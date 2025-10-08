import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MyViewModel : ViewModel() {

    private var channel: ManagedChannel? = null

    fun makeGrpcCall() {
        // Initialize the channel if it's null or shut down
        if (channel == null || channel!!.isShutdown) {
            channel = ManagedChannelBuilder.forAddress("your_server_address", 8080)
                .usePlaintext() // Use encryption in production
                .build()
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Your gRPC client and call logic here
                // val client = YourGrpcServiceGrpc.newBlockingStub(channel)
                // val request = YourRequest.newBuilder().build()
                // val response = client.yourRpcMethod(request)
                // Handle response
            } catch (e: Exception) {
                // Handle exceptions
            }
            // No shutdown here if you plan to reuse the channel
        }
    }

    // Called when the ViewModel is no longer used and will be destroyed.
    override fun onCleared() {
        super.onCleared()
        channel?.let {
            if (!it.isShutdown) {
                try {
                    it.shutdown().awaitTermination(5, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    // Optionally, call shutdownNow() as a fallback
                    it.shutdownNow()
                }
            }
        }
    }
}
    