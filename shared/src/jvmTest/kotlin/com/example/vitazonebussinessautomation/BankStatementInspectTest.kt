package com.example.vitazonebussinessautomation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BankStatementInspectTest {

    @Test
    fun testBankStatementFileExists() {
        val file = File("/Users/macbookpro/AndroidStudioProjects/VitazoneBussinessAutomation/f5b7b194f9002846671963a4ce4cba6b_1792756750.xlsx")
        assertTrue(file.exists())
    }
}
