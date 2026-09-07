package com.opennxt.resources.config.params

import com.opennxt.resources.DefaultStateChecker
import com.opennxt.resources.config.vars.ScriptVarType

data class ParamDefinition(
    var defaultInt: Int = 0,
    var defaultString: String = "null",
    /**
 * WHAT OPCODE 4 MEANS IS UNRESOLVED. WHAT THIS FIELD DOES IS NOT: NOTHING.
     */
    var membersOnly: Boolean = true,
    var type: ScriptVarType = ScriptVarType.INT,
    /**
     * The raw opcode-101 type id when [ScriptVarType] does not list it, else null.
     */
    var unknownTypeId: Int? = null
): DefaultStateChecker {
    companion object {
        private val DEFAULT = ParamDefinition()
    }

    override fun isDefault(): Boolean = this == DEFAULT
}