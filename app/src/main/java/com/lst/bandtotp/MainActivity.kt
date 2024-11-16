package com.lst.bandtotp

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.lst.bandtotp.ui.theme.BandtotpTheme
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader


class MainActivity : ComponentActivity() {

    private lateinit var nodeId: String
    private lateinit var curNode:Node
    private lateinit var nodeApi:NodeApi
    private val handler = Handler(Looper.getMainLooper())
    // 全局状态
    private var logTextState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nodeApi= Wearable.getNodeApi(this)
        enableEdgeToEdge()
        setContent {
            BandtotpTheme(){
                MainContent()
            }
        }
    }
    private fun openApp(){
        nodeApi.isWearAppInstalled(nodeId)
            .addOnSuccessListener { nodeApi.launchWearApp(nodeId, "pages/index").addOnSuccessListener {
                //打开穿戴设备端应用成功
                log("打开手环端软件成功")
            }.addOnFailureListener {
                log("打开手环端软件失败")
                //sendMessageToWearable("{\"name\":\"microsoft\",\"key\":\"EP5O5BC3NZVEE7YI\",\"usr\":\"lesetong\"}".toByteArray())
            }
            }
            .addOnFailureListener {
                log("手环未安装小程序")
                Toast.makeText(
                    this,
                    "手环未安装小程序！如果已经安装，请尝试重启手环",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    //发送信息
    private fun sendMessageToWearable(message: String) {
        val messageApi = Wearable.getMessageApi(this)
        if (::nodeId.isInitialized) {
            messageApi.sendMessage(nodeId, message.toByteArray())
                .addOnSuccessListener {
                    Toast.makeText(this, "Message sent successfully", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to send message: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
        }
    }
    // 查询已连接的设备
    private fun queryConnectedDevices() {
        nodeApi.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                curNode = nodes[0]
                nodeId = curNode.id
                //connectedDeviceText.text =getString(R.string.connStat)+curNode.name
                log(curNode.name)
                checkAndRequestPermissions()
            } else {
                //connectedDeviceText.text =getString(R.string.connStat)+"None"
            }
        }.addOnFailureListener { e ->
            Toast.makeText(
                this,
                "Failed to get connected devices: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 申请权限
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            // 请求蓝牙权限
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), 1001)
        }
        val authApi = Wearable.getAuthApi(this)
        if (::nodeId.isInitialized) {
            val did =nodeId  // 填入你的设备 ID
            authApi.checkPermission(did, Permission.DEVICE_MANAGER)
                .addOnSuccessListener { granted ->
                    if (!granted) {
                        authApi.requestPermission(did, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener {
                                log("Permissions granted")
//                                listenForMessages()
                            }.addOnFailureListener { e ->
                                Toast.makeText(this, "Failed to request permissions: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        log("Permissions already granted")
//                        listenForMessages()
                    }
                }.addOnFailureListener { e ->
                    e.message?.let { log(it) }
                }}
    }
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var connectedDeviceText by remember { mutableStateOf("设备未连接") }
        var logText by remember { logTextState }
        // 启动文件选择器
        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                // 通过 context 获取 contentResolver
                val contentResolver = context.contentResolver
                val datas=mutableListOf<Any>()
                processFileLineByLine(contentResolver, it) { line ->
                    var temp=parseTotpUri(line)
                    temp?.let { it1 -> log(it1.name) }
                    temp?.let { it1 -> datas.add(it1) }
                }
                sendMessageToWearable("{\"list\":${datas}}")
                //log(datas)
                //log(readTextFromUri(contentResolver, it))
            }
        }
        fun startUpload(){
            if(::nodeId.isInitialized) {
                openApp()
                pickFileLauncher.launch("text/plain")
            }else{
                Toast.makeText(this, "未连接到设备", Toast.LENGTH_SHORT).show()
            }
        }
        LaunchedEffect(Unit) {
            while (!(::nodeId.isInitialized)) {
                // 更新文本
                queryConnectedDevices()
                // 延迟一段时间再更新
                delay(1000) // 每秒更新一次
            }
            connectedDeviceText = "设备:${curNode.name}"
        }

        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize().systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 已连接设备信息卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // 使用主题颜色
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = connectedDeviceText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Button(
                onClick = { startUpload() } ,
                modifier = Modifier
                        .fillMaxWidth()
            ) {
                Text("选择文本文档")
            }
            // 日志显示卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // 使用主题颜色
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "LOGS",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer // 动态颜色
                )
                Column(modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            //关于
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // 使用主题颜色
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer // 动态颜色
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BandTOTP by lesetong\n本软件使用MIT协议开源\nCopyright (c) 2024 lesetong",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer // 动态颜色
                    )
                }
            }
        }
    }
    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        BandtotpTheme(){
            MainContent()
        }
    }
    // 方法用于添加日志
    private fun log(message:Any) {
        logTextState.value +="$message\n"
    }
    private fun readTextFromUri(contentResolver: ContentResolver, uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        }
        return stringBuilder.toString()
    }

    private data class TOTPInfo(
        val name: String,//issuer
        val usr: String,//account
        val key: String,//secret
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val period: Int = 30
    ){
        override fun toString(): String {
            return """{
            "name": "$name",
            "usr": "$usr",
            "key": "$key",
            "algorithm": "$algorithm",
            "digits": $digits,
            "period": $period
        }""".trimIndent()
        }}

    private fun parseTotpUri(totpUri: String): TOTPInfo? {
        try {
            val uri = Uri.parse(totpUri)

            // 检查URI的scheme是否是otpauth以及是否是totp类型
            if (uri.scheme != "otpauth" || uri.host != "totp") {
                return null
            }

            // 从路径中提取 issuer 和 account
            val path = uri.path ?: return null
            val splitPath = path.split(":")
            if (splitPath.size != 2) {
                return null
            }

            val issuerFromPath = splitPath[0].trim('/')
            val account = splitPath[1]

            // 从查询参数中获取 secret 和其他可选参数
            val secret = uri.getQueryParameter("secret") ?: return null
            val issuerFromQuery = uri.getQueryParameter("issuer") ?: issuerFromPath
            val algorithm = uri.getQueryParameter("algorithm") ?: "SHA1"
            val digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
            val period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30

            // 返回解析后的 TOTP 信息
            return TOTPInfo(
                name = issuerFromQuery,
                usr = account,
                key = secret,
                algorithm = algorithm,
                digits = digits,
                period = period
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    private fun processFileLineByLine(contentResolver: ContentResolver, uri: Uri, processLine: (String) -> Unit) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // 对每一行进行处理
                    processLine(line!!)
                }
            }
        }
    }
}



