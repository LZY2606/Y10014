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

class Probe3Test {
    @[Test JsName("probe_group_validate_swallowed")]
    fun probeGroupValidateSwallowed() {
        class Group : OptionGroup() {
            val g by option().optTransformAll { it.lastOrNull() ?: "x" }
                .validate { error("VALIDATOR RAN") }
        }
        class C : TestCommand() {
            val group by Group()
        }
        try {
            C().parse("--g y")
            println("PROBE3: no exception -> group validate was swallowed")
        } catch (e: Throwable) {
            println("PROBE3 exception: ${e::class.simpleName}: ${e.message}")
        }
    }
}
