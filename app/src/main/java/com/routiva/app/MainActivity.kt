package com.routiva.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var address by remember { mutableStateOf("") }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Routiva",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text("Smart delivery route planning")
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Enter delivery address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add address")
                    }
                }
            }
        }
    }
}
