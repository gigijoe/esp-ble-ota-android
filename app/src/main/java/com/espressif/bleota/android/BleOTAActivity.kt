package com.espressif.bleota.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanResult
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.espressif.bleota.android.databinding.BleOtaActivityBinding
import com.espressif.bleota.android.message.BleOTAMessage
import com.espressif.bleota.android.message.CommandAckMessage
import com.espressif.bleota.android.message.EndCommandAckMessage
import com.espressif.bleota.android.message.StartCommandAckMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BleOTAActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BleOTAActivity"
    }

    private val mBinding by lazy(LazyThreadSafetyMode.NONE) {
        BleOtaActivityBinding.inflate(layoutInflater)
    }

    private lateinit var mScanResult: ScanResult
    private lateinit var mBinUri: Uri

    private var mOtaClient: BleOTAClient? = null

    private val mStatusList = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)

        mScanResult = intent.getParcelableExtra(BleOTAConstants.KEY_SCAN_RESULT)!!
        mBinUri = intent.getParcelableExtra(BleOTAConstants.KEY_BIN_URI)!!

        mBinding.recyclerView.adapter = StatusAdapter()

        mBinding.connectBtn.setOnClickListener {
            mBinding.connectBtn.isEnabled = false
            mBinding.otaBtn.isEnabled = false
            connect()
        }
        mBinding.otaBtn.setOnClickListener {
            mBinding.otaBtn.isEnabled = false
            ota()
        }

        connect()
    }

    override fun onDestroy() {
        super.onDestroy()

        close()
    }

    private fun connect() {
        close()
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "connect: start")
            val binData = contentResolver.openInputStream(mBinUri)?.use {
                it.readBytes()
            }!!
            mOtaClient = BleOTAClient(applicationContext, mScanResult.device, binData)
            mOtaClient?.connect(GattCallback())
        }
    }

    private fun close() {
        Log.d(TAG, "close")
        mOtaClient?.close()
        mOtaClient = null
    }

    private fun ota() {
        mOtaClient?.ota()
    }

    private fun updateStatus(message: String, connected: Boolean) {
        runOnUiThread {
            mStatusList.add(message)
            mBinding.recyclerView.scrollToPosition(mStatusList.lastIndex)
            if (connected) {
                mBinding.progressBar.visible()
                mBinding.otaBtn.isEnabled = true
            } else {
                close()
                mBinding.progressBar.gone()
                mBinding.otaBtn.isEnabled = false
            }
        }
    }

    private fun updateProgress(message: String) {
        runOnUiThread {
            mStatusList.add(message)
            mBinding.recyclerView.scrollToPosition(mStatusList.lastIndex)
            mBinding.otaBtn.isEnabled = false
        }
    }

    private inner class StatusHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
    }

    private inner class StatusAdapter : RecyclerView.Adapter<StatusHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatusHolder {
            val itemView =
                layoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)
            return StatusHolder(itemView)
        }

        override fun onBindViewHolder(holder: StatusHolder, position: Int) {
            holder.text1.text = mStatusList[position]
        }

        override fun getItemCount(): Int {
            return mStatusList.size
        }

    }

    private inner class GattCallback : BleOTAClient.GattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when {
                status.isGattFailed() -> {
                    updateStatus("Status error: $status", false)
                }
                newState == BluetoothGatt.STATE_DISCONNECTED -> {
                    updateStatus("Disconnected", false)
                }
                newState == BluetoothGatt.STATE_CONNECTED -> {
                    updateStatus("Connected", true)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            val result = if (status.isGattSuccess()) {
                "success"
            } else {
                "failed, status=$status"
            }
            updateStatus("Request MTU $mtu $result", true)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status.isGattFailed()) {
                updateStatus("Discover services failed, status=$status", false)
                return
            }
            if (mOtaClient?.service == null) {
                updateStatus("Discover service failed", false)
                return
            }
            if (mOtaClient?.recvFwChar == null) {
                updateStatus("Discover FW CHAR failed", false)
                return
            }
            if (mOtaClient?.commandChar == null) {
                updateStatus("Discover COMMAND CHAR failed", false)
                return
            }

            updateStatus("Discover service and char completed", true)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status.isGattFailed()) {
                updateStatus(
                    "Set notification enabled failed, status=$status, char=${descriptor.characteristic.uuid}",
                    false
                )
                return
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status.isGattFailed()) {
                updateStatus("CharacteristicWrite failed, status=$status", false)
            }
        }

        override fun onOTA(message: BleOTAMessage) {
            if (message is StartCommandAckMessage) {
                if (message.status == CommandAckMessage.STATUS_ACCEPT) {
                    updateStatus("Start OTA ...", true)
                } else {
                    if (message.status == CommandAckMessage.STATUS_REFUSE) {
                        updateStatus("Device refuse OTA start request", false)
                    }
                }
            } else if (message is EndCommandAckMessage) {
                if (message.status == CommandAckMessage.STATUS_ACCEPT) {
                    updateStatus("OTA Complete!!", false)
                } else if (message.status == CommandAckMessage.STATUS_REFUSE) {
                    updateStatus("Device refuse OTA end request", false)
                }
            }
        }

        override fun onError(code: Int) {
            updateStatus("Error: $code", false)
        }

        override fun onProgress(progress: Int) {
            updateProgress("Progress " + progress + "%");
        }
    }
}