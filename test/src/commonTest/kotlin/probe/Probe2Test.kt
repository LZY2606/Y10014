package probe

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll as optTransformAll
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.testing.TestCommand
import com.github.ajalt.clikt.testing.parse
import kotlin.js.JsName
import kotlin.test.Test

class Probe2Test {
    @[Test JsName("probe_group_identity")]
    fun probeGroupIdentity() {
        val log = mutableListOf<String>()
        class Group : OptionGroup() {
            val g1 by option().optTransformAll { log += "g1.transformAll"; it.lastOrNull() }
            val g2 by option().optTransformAll { log += "g2.transformAll"; it.lastOrNull() }
                .validate { log += "g2.postValidate" }
        }
        class C : TestCommand() {
            val group by Group()
        }
        val c = C()
        c.parse("")
        for (o in c.registeredOptions()) {
            println("PROBE2 opt ${o.names} group=${(o as com.github.ajalt.clikt.core.GroupableOption).parameterGroup}")
        }
        println("PROBE2 groups=${c.registeredParameterGroups()}")
       
        println("PROBE2 log=$log")
    }
}
