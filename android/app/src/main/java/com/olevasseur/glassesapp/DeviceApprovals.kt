package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Only physical callbacks and the approval card may call decide. Never a client tool. */
enum class ApprovalMethod(val wire: String) { NOD("nod"), BUTTON("button"), SCREEN("screen") }

data class DeviceApproval(val id: String, val tier: Int, val summary: String, val details: String,
                          val hash: String, val hardware: Boolean = false, val deciding: Boolean = false) {
    val displayText: String get() = "$summary\n$details"
}

/** Main-thread state machine; backend status is the sole source of an action to review. */
class DeviceApprovals(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val status: suspend (String) -> JSONObject,
    private val approve: suspend (String, String) -> JSONObject,
    private val deny: suspend (String) -> JSONObject,
    private val show: (DeviceApproval) -> Boolean,
    private val hide: () -> Unit,
    private val onResult: (String, JSONObject) -> Unit,
    private val resetMotion: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<DeviceApproval?>(null)
    val state = mutableState.asStateFlow()
    private val seen = LinkedHashSet<String>()
    private var timer: Job? = null
    private var deadline = 0L
    private var closed = false

    suspend fun offer(response: JSONObject) {
        if (closed) return
        if (response.optString("status") != "needs_confirmation") return
        val id = response.optString("approval_id")
        if (!id.matches(Regex("[a-f0-9]{32}")) || !seen.add(id)) return
        while (seen.size > 128) seen.remove(seen.first())
        // Do not replace a card under a finger or let one gesture approve a queue.
        if (state.value != null) {
            val result = try { deny(id) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { toolError("Could not deny the additional request; use the dashboard") }
            onResult(id, result)
            return
        }
        // Reserve before the network suspension: simultaneous tool replies cannot replace it.
        mutableState.value = DeviceApproval(id, 2, "Loading approval…", "", "", deciding = true)
        try {
            val verified = status(id)
            if (closed || state.value?.id != id) return
            if (verified.optString("status") != "needs_confirmation") { finish(id, verified); return }
            val tier = verified.getInt("tier")
            require(tier == 1 || tier == 2)
            val details = verified.getJSONObject("details").toString(2)
            val request = DeviceApproval(id, tier, verified.getString("summary"), details, verified.getString("payload_hash"))
            resetMotion()
            val notified = show(request)
            mutableState.value = request.copy(hardware = notified && request.displayText.length <= 4000)
            deadline = now() + 60_000
            timer = scope.launch {
                while (state.value?.id == id) {
                    delay(2_000)
                    if (now() >= deadline) { reject(id); return@launch }
                    try {
                        val result = status(id)
                        if (result.optString("status") !in setOf("needs_confirmation", "executing")) {
                            finish(id, result); return@launch
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Timeout still denies. */ }
                }
            }
        } catch (e: CancellationException) { mutableState.value = null; hide(); throw e }
        catch (_: Exception) { finish(id, toolError("Approval unavailable; use Dashboard → Approvals")) }
    }

    fun decide(id: String, method: ApprovalMethod): Boolean {
        val request = state.value ?: return false
        if (request.id != id || request.deciding) return true
        if (now() >= deadline) { reject(id); return true }
        if (method != ApprovalMethod.SCREEN && !request.hardware) return true
        if (method == ApprovalMethod.NOD && request.tier != 1) return true
        submit(id) { approve(id, method.wire) }
        return true
    }

    fun gesture(gesture: HeadGesture): Boolean {
        val id = state.value?.id ?: return false
        if (gesture == HeadGesture.SHAKE) reject(id) else decide(id, ApprovalMethod.NOD)
        return true // Consume forbidden nods too; never advance cooking or answer a model question.
    }

    fun capture(): Boolean = state.value?.let { decide(it.id, ApprovalMethod.BUTTON) } ?: false

    fun reject(id: String) {
        if (state.value?.id != id || state.value?.deciding == true) return
        submit(id) { deny(id) }
    }

    private fun submit(id: String, action: suspend () -> JSONObject) {
        mutableState.value = state.value?.copy(deciding = true)
        timer?.cancel(); timer = null
        hide()
        scope.launch {
            val result = try { action() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { toolError("Approval request failed; outcome unknown. Check dashboard before retrying.") }
            finish(id, result)
        }
    }

    private fun finish(id: String, result: JSONObject) {
        if (state.value?.id != id) return
        mutableState.value = null
        hide()
        onResult(id, result)
    }

    fun close() { closed = true; timer?.cancel(); timer = null; mutableState.value = null; hide() }
}
