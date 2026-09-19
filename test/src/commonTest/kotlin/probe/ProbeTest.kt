package probe

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.transformAll as argTransformAll
import com.github.ajalt.clikt.parameters.arguments.validate as argValidate
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.transformAll as optTransformAll
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.testing.TestCommand
import com.github.ajalt.clikt.testing.parse
import kotlin.js.JsName
import kotlin.test.Test

class ProbeTest {
    @[Test JsName("probe_sub_help")]
    fun probeSubHelp() {
        class Sub : TestCommand()
        class Parent : TestCommand() {
            val req by option().int().required()
            init { subcommands(Sub()) }
        }
        try {
            Parent().parse("sub --help")
            println("PROBE: no exception")
        } catch (e: Throwable) {
            println("PROBE subhelp exception: ${e::class.qualifiedName}: ${e.message}")
        }
    }

    @[Test JsName("probe_order")]
    fun probeOrder() {
        val log = mutableListOf<String>()
        class Group : OptionGroup() {
            val g by option()
                .optTransformAll { log += "group.transformAll"; it.lastOrNull() }
                .validate { log += "group.postValidate" }
        }
        class C : TestCommand() {
            val group by Group()
            val arg by argument()
                .argTransformAll { log += "arg.transformAll"; it.joinToString(" ") }
                .argValidate { log += "arg.postValidate" }
            val opt by option()
                .optTransformAll { log += "opt.transformAll"; it.lastOrNull() }
                .validate { log += "opt.postValidate" }
        }
        C().parse("hello --opt world")
        println("PROBE order: $log")
    }

    @[Test JsName("probe_cycle")]
    fun probeCycle() {
        class C : TestCommand() {
            val a: String? by option().defaultLazy { b ?: "" }
            val b: String? by option().defaultLazy { a ?: "" }
            override fun run_() {
                println("PROBE cycle run: a=$a b=$b")
            }
        }
        try {
            C().parse("")
            println("PROBE cycle: no exception")
        } catch (e: Throwable) {
            println("PROBE cycle exception: ${e::class.qualifiedName}: ${e.message}")
        }
    }

    @[Test JsName("probe_ref_default")]
    fun probeRefDefault() {
        class CLazyFirst : TestCommand() {
            val lazy: String? by option().defaultLazy { plain + "L" }
            val plain: String by option().default("P")
            override fun run_() {
                println("PROBE lazy-first: plain=$plain lazy=$lazy")
            }
        }
        class CPlainFirst : TestCommand() {
            val plain: String by option().default("P")
            val lazy: String? by option().defaultLazy { plain + "L" }
            override fun run_() {
                println("PROBE plain-first: plain=$plain lazy=$lazy")
            }
        }
        for (mk in listOf({ CLazyFirst() }, { CPlainFirst() })) {
            try {
                mk().parse("")
            } catch (e: Throwable) {
                println("PROBE ref-default exception: ${e::class.qualifiedName}: ${e.message}")
            }
        }
    }
}
