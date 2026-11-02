package dev.macdroid.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run with scripts/test-peer.sh, which starts the isolated Swift peer on loopback. */
@RunWith(AndroidJUnit4::class)
class PeerIntegrationTest {
    @Test fun androidAndSwiftExchangeTextAndBinaryAndRetrySafely() {
        val encoded = Base64.encodeToString(ByteArray(32) { 7 }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val relayMode = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("relay") == "true"
        val link = if (relayMode) "macdroid://127.0.0.1:1?key=$encoded&relay=ws%3A%2F%2F10.0.2.2%3A8787" else "macdroid://10.0.2.2:49272?key=$encoded"
        BridgeClient(Pairing.parse(link)).use { client ->
        val items = client.call("list").getJSONArray("items")
        val fileItem = items.getJSONObject(0)
        assertEquals("Shared from Mac – café.txt", fileItem.getString("filename"))
        val downloaded = Base64.decode(client.call("get", id = fileItem.getString("id")).getString("data"), Base64.DEFAULT)
        assertArrayEquals("Swift → Android · café 📎".toByteArray(), downloaded)
        val file = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "file").put("title", "Android file")
            .put("filename", "../../android.bin").put("mime", "application/octet-stream").put("size", 0)
            .put("createdAt", System.currentTimeMillis() / 1000.0).put("source", "Android").put("pinned", false)
        val payload = ByteArray(65537) { (it % 251).toByte() }
        client.call("put", file, payload)
        client.call("put", file, byteArrayOf(1, 2, 3)) // Lost-ack retry must not overwrite the original.
        assertArrayEquals(payload, Base64.decode(client.call("get", id = file.getString("id")).getString("data"), Base64.DEFAULT))
        val text = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "text").put("title", "peer-complete")
            .put("text", "Android → Swift · success").put("size", 0).put("createdAt", System.currentTimeMillis() / 1000.0).put("source", "Android").put("pinned", false)
        client.call("put", text)
        }
    }
}
