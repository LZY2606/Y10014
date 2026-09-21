package com.github.ajalt.clikt.parsers

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.transformAll
import com.github.ajalt.clikt.parameters.arguments.validate
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.testing.TestCommand
import com.github.ajalt.clikt.testing.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.JsName
import kotlin.test.Test

class FinalizationOrderTest {
    @[Test JsName("subcommand_help_wins_over_parent_required_option")]
    fun `subcommand help wins over parent required option`() {
        class Sub : TestCommand(called = false)
        class C : TestCommand(called = false) {
            val req by option().required()
        }

        val e = shouldThrow<PrintHelpMessage> {
            C().subcommands(Sub()).parse("sub --help")
        }
        e.error shouldBe false
    }

    @[Test JsName("transformAll_order_is_argument_option_group")]
    fun `transformAll order is argument option group`() {
        val calls = mutableListOf<String>()

        class G : OptionGroup() {
            val g by option().transformAll { calls += "group-option"; it.lastOrNull() }
        }

        class C : TestCommand() {
            val a by argument().transformAll { calls += "argument"; it.joinToString() }
            val o by option().transformAll { calls += "option"; it.lastOrNull() }
            val g by G()

            override fun run_() {
                calls shouldBe listOf("argument", "option", "group-option")
            }
        }

        C().parse("x")
    }

    @[Test JsName("postValidate_order_is_option_group_argument")]
    fun `postValidate order is option group argument`() {
        val calls = mutableListOf<String>()

        class G : OptionGroup() {
            val g by option().validate { calls += "group-option" }
        }

        class C : TestCommand() {
            val a by argument().validate { calls += "argument" }
            val o by option().validate { calls += "option" }
            val g by G()

            override fun run_() {
                calls shouldBe listOf("option", "group-option", "argument")
            }
        }

        C().parse("--o v --g w x")
    }

    @[Test JsName("defaultLazy_mutual_reference_throws_on_read")]
    fun `defaultLazy mutual reference throws on read`() {
        class C : TestCommand(called = false) {
            val a: String by option().defaultLazy { b }
            val b: String by option().defaultLazy { a }
        }

        val e = shouldThrow<IllegalStateException> { C().parse("") }
        e.message shouldBe "Cannot read from option delegate before parsing command line"
    }

    @[Test JsName("defaultLazy_declaration_order_does_not_change_result")]
    fun `defaultLazy declaration order does not change result`() {
        class C1 : TestCommand() {
            val a: String by option().defaultLazy { b }
            val b: String by option().default("d")

            override fun run_() {
                a shouldBe "d"
                b shouldBe "d"
            }
        }

        class C2 : TestCommand() {
            val b: String by option().default("d")
            val a: String by option().defaultLazy { b }

            override fun run_() {
                a shouldBe "d"
                b shouldBe "d"
            }
        }

        C1().parse("")
        C2().parse("")
    }
}
