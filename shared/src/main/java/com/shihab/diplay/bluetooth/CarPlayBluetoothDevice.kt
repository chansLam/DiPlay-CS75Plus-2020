package com.shihab.diplay.bluetooth

data class CarPlayBluetoothDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean = false
)
