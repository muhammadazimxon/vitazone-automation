package com.example.vitazonebussinessautomation.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class Company(
    val inn: String,
    val name: String
) {
    val normalizedInn: String
        get() = inn.replace(Regex("\\D"), "")
}
