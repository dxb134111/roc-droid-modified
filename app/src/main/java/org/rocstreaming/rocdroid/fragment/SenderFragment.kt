package org.rocstreaming.rocdroid.fragment
import android.Manifest
import android.content.Context.MEDIA_PROJECTION_SERVICE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.rocstreaming.rocdroid.R
import org.rocstreaming.rocdroid.SenderReceiverService
import org.rocstreaming.rocdroid.component.CopyBlock
import java.net.InetAddress
import java.net.UnknownHostException

private const val LOG_TAG = "[rocdroid.fragment.SenderFragment]"

class SenderFragment : Fragment() {
    private lateinit var selectedAudioSource: String
    private lateinit var audioSources: Array<String>
    private var selectedAudioSourceIndex: Int = 0
    private lateinit var senderService: SenderReceiverService
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var receiverIpEdit: EditText
    private lateinit var usePlaybackCapture: TextView
    private var projection: MediaProjection? = null // 改为可空，避免未初始化崩溃
    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(LOG_TAG, "Create Sender Fragment View")
        val view = inflater.inflate(R.layout.sender_fragment, container, false)
        usePlaybackCapture = view.findViewById(R.id.audio_source)
        receiverIpEdit = view.findViewById(R.id.receiverIp)
        prefs = activity?.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)!!

        audioSources = arrayOf(
            getString(R.string.sender_audio_source_current_apps),
            getString(R.string.sender_audio_source_microphone)
        )

        // 读取保存的IP/域名，无则用默认值
        val savedIpOrDomain = prefs.getString("receiver_ip", null)
            ?: resources.getString(R.string.default_receiver_ip)
        receiverIpEdit.setText(savedIpOrDomain)

        usePlaybackCapture.text = audioSources[selectedAudioSourceIndex]
        view.findViewById<CopyBlock>(R.id.sourcePortValue)?.setText("10001")
        view.findViewById<CopyBlock>(R.id.repairPortValue)?.setText("10002")
        view.findViewById<TextView>(R.id.portForSource).text =
            getString(R.string.receiver_sender_port_for_source).format(2)
        view.findViewById<TextView>(R.id.portForRepair).text =
            getString(R.string.receiver_sender_port_for_repair).format(3)

        // 音频源选择弹窗点击事件
        val showAudioSourceDialog: ConstraintLayout =
            view.findViewById(R.id.audio_source_dialog_button)
        showAudioSourceDialog.setOnClickListener {
            showAudioSourcesDialog()
        }

        // 启动/停止发送器按钮点击事件
        view.findViewById<Button>(R.id.startSenderButton).setOnClickListener {
            startStopSender()
        }

        // 媒体投影结果回调（用于捕获应用音频）
        projectionLauncher = registerForActivityResult(StartActivityForResult()) { result ->
            if (result.data != null) {
                projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
                resolveIpOrDomainAndStartSender(projection) // 解析后启动发送器
            }
        }

        // 录音权限请求回调
        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (isGranted) {
                    AlertDialog.Builder(requireActivity()).apply {
                        setTitle(R.string.allow_mic_title)
                        setMessage(getString(R.string.allow_mic_ok_message))
                        setCancelable(false)
                        setPositiveButton(R.string.ok) { _, _ -> startStopSender() }
                    }.show()
                }
            }

        return view
    }

    fun onServiceConnected(
        service: SenderReceiverService,
        showActiveIcon: () -> Unit,
        hideActiveIcon: () -> Unit
    ) {
        Log.d(LOG_TAG, "Add Receiver State Changed Listener")
        senderService = service
        senderService.setSenderStateChangedListeners(senderChanged = { state: Boolean ->
            activity?.runOnUiThread {
                // 更新按钮文本（启动/停止）
                activity?.findViewById<Button>(R.id.startSenderButton)?.text =
                    getString(if (state) R.string.stop_sender else R.string.start_sender)
                // 显示/隐藏活跃图标
                if (state) showActiveIcon() else hideActiveIcon()
            }
        })
    }

    // 音频源选择弹窗
    private fun showAudioSourcesDialog() {
        Log.d(LOG_TAG, "Showing Select Audio Source Dialog")
        selectedAudioSource = audioSources[selectedAudioSourceIndex]
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.dialog_choose_audio_source)
            .setSingleChoiceItems(audioSources, selectedAudioSourceIndex) { _, which ->
                selectedAudioSourceIndex = which
                selectedAudioSource = audioSources[which]
            }.setPositiveButton("Ok") { _, _ ->
                Log.d(LOG_TAG, String.format("Selected Audio Source: %s", selectedAudioSource))
                view?.findViewById<TextView>(R.id.audio_source)?.text = selectedAudioSource
            }.setNegativeButton("Cancel") { dialog, _ ->
                Log.d(LOG_TAG, "Dismiss Audio Source Dialog")
                dialog.dismiss()
            }.show()
    }

    // 判断是否使用“应用音频捕获”
    fun isUsePlayback(): Boolean {
        return usePlaybackCapture.text == audioSources[0]
    }

    // 核心：启动/停止发送器（含域名解析逻辑）
    private fun startStopSender() {
        if (senderService.isSenderAlive()) {
            // 停止发送器（原有逻辑不变）
            Log.d(LOG_TAG, "Stopping Sender")
            senderService.stopSender()
            projection?.stop() // 停止媒体投影，避免资源占用
        } else {
            // 启动发送器（新增域名解析流程）
            Log.d(LOG_TAG, "Starting Sender")
            val editor = prefs.edit()
            editor.putBoolean("playback_capture", isUsePlayback())
            editor.putString("receiver_ip", receiverIpEdit.text.toString()) // 保存IP/域名
            editor.apply()

            // 先检查录音权限
            if (!askForRecordAudioPermission()) return

            if (isUsePlayback()) {
                // 场景1：捕获应用音频（需先获取媒体投影）
                senderService.preStartSender()
                projectionManager =
                    activity?.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projectionIntent = projectionManager.createScreenCaptureIntent()
                activity?.startForegroundService(projectionIntent)
                projectionLauncher.launch(projectionIntent)
            } else {
                // 场景2：使用麦克风（直接解析启动）
                resolveIpOrDomainAndStartSender(null)
            }
        }
    }

    // 新增：解析IP/域名，对接原有发送器启动逻辑
    private fun resolveIpOrDomainAndStartSender(mediaProjection: MediaProjection?) {
        val inputContent = receiverIpEdit.text.toString().trim()
        // 1. 校验输入不为空
        if (inputContent.isEmpty()) {
            showToast("请输入IP或域名")
            return
        }

        // 2. 子线程解析（避免阻塞主线程）
        Thread {
            val targetIp: String? = try {
                if (isIpAddress(inputContent)) {
                    // 是IP，直接使用
                    inputContent
                } else {
                    // 是域名，解析为IP
                    Log.d(LOG_TAG, "Resolving domain: $inputContent")
                    val inetAddress = InetAddress.getByName(inputContent)
                    val resolvedIp = inetAddress.hostAddress
                    Log.d(LOG_TAG, "Domain resolved to: $resolvedIp")
                    resolvedIp
                }
            } catch (e: UnknownHostException) {
                // 解析失败（域名无效/网络问题）
                Log.e(LOG_TAG, "Domain resolution failed: ${e.message}")
                showToast("域名解析失败，请检查输入或网络")
                null
            }

            // 3. 解析成功，主线程启动发送器
            targetIp?.let { ip ->
                activity?.runOnUiThread {
                    try {
                        senderService.startSender(ip, mediaProjection)
                        showToast("发送器已启动（目标IP：$ip）")
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to start sender: ${e.message}")
                        showToast("发送器启动失败：${e.message}")
                    }
                }
            }
        }.start()
    }

    // 新增：判断是否为IPv4地址（简单正则校验）
    private fun isIpAddress(input: String): Boolean {
        if (input.isBlank()) return false
        val ipv4Regex = "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"
        return input.matches(ipv4Regex.toRegex())
    }

    // 新增：统一Toast显示（确保主线程调用）
    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // 原有：录音权限请求逻辑（无修改）
    private fun askForRecordAudioPermission(): Boolean = when {
        ContextCompat.checkSelfPermission(
            requireActivity(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED -> {
            true
        }
        shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
            AlertDialog.Builder(requireActivity()).apply {
                setTitle(getString(R.string.allow_mic_title))
                setMessage(getString(R.string.allow_mic_message))
                setCancelable(false)
                setPositiveButton(R.string.ok) { _, _ ->
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }.show()
            false
        }
        else -> {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
}
