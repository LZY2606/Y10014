# Clikt 参数解析链路交底

本文基于当前 checkout，描述命令行从 token 切分，到每个参数 value 落定，再到 `validate { }` 回调被调用的完整链路。所有代码引用格式为 `模块/相对路径/文件.kt:行号`，行号对应当前源码。

## 第一部分：调用链

### 1. 公开入口与两阶段总览

1. `CoreCliktCommand.parse(argv)`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/CoreCliktCommand.kt:106`）转调 `CommandLineParser.parseAndRun`。
2. `parseAndRun`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:70`）把工作切成两段：先 `parse(command, argv)`（只做 token 分派、不抛解析期错误），再 `run(result.invocation, runCommand)`（终局化 + 执行业务 `run`）。
3. `parse`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:105`）调用内部 `parseArgv`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:8`），产出一棵 `CommandInvocation` 树（类型定义见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:43`）。
4. `run`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:86`）调用 `flatten()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:101`）。`FlatInvocations.iterator()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:122`）做了两遍遍历：第一遍对每个 invocation 执行 `finalizeEagerOptions`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:127`），第二遍边吐节点边执行 `finalizeCommand`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:128`）；随后 `run` 在每个终局化完成的命令上调用业务回调（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:92`，对 `CoreCliktCommand` 就是 `it.run()`，见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/CoreCliktCommand.kt:107`）。

### 2. 解析阶段：argv 变成 invocation 树

5. `parseArgv`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:8`）先 `rootCommand.resetContext()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:12`；实现 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/BaseCliktCommand.kt:114`），再进入递归函数 `parseCommandArgv`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:18`）。
6. 每一层由 `CommandParser.parse()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:87`）扫描 token：`splitOptionPrefixes()` 建名字索引（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:164`），`consumeTokens()` 逐 token 分派（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:95`）：别名（`:101`）、@argfile（`:105`）、`--`（`:116`）、长选项（`:123` 调 `parseLongOpt`，`:220`）、短选项（`:132` 调 `parseShortOpt`，`:241`）、子命令名（`:139`，命中后 `break` 并记录 `subcommand`）、否则收进 `argumentTokens`（`:145`）。
7. 选项与其裸值由 `parseOptValues`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:282`）切出 `OptionInvocation(name, values)`；注意这一步只做贪婪收集，不做转换、不判必填，值数不够留到终局化报告（注释见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:307`）。
8. 位置 token 在 `parseArguments()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:312`）里按 `command._arguments` 的声明顺序切成 `ArgumentInvocation`；多余 token 由 `handleExcessArgs`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:343`）记成 `NoSuchSubcommand`（`:351`）或 `NoSuchArgument`（`:355`）。
9. 解析期错误不抛：选项解析错误在 `consumeOptionParse` 里进 `errors`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:208`）；`makeResult` 汇总错误、给 `UsageError` 补 context、并置位 `errorEncountered`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:183`，标志位置位于 `:187`），然后在 `:189` 决定：**只有本层 `errors` 为空时才把 `subcommand` 交出去**。
10. 回到 `parseCommandArgv`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:28`）：只要父层交出了下一个命令，就递归解析子层，并把子层 invocation 挂进 `subcommandInvocations`（`:33`，组装见 `CommandParseResult.toInvocation`，`:375`）。

### 3. 终局化阶段（终点 A：普通选项 value 落定）

11. `finalizeEagerOptions`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:117`）：先处理 completion envvar（`:120`），再用 `getOpts`（`:176`，按 `Option.eager` 分区）和 `getInvs`（`:171`）取出 eager 选项，在 `:127` 调 `finalizeOptions`，并立刻对“被调用过的 eager 选项”做 postValidate（`:128`）。自动注册的 `--help` 就是一个 eager flag 选项：注册点 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/BaseCliktCommand.kt:129`（`eagerOption(...) { throw PrintHelpMessage(context) }`），其 action 在 `validate { if (it) action() }` 中触发，见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/EagerOption.kt:58`。
12. `finalizeCommand`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:138`）处理非 eager 参数：`:141` 取组、`:142` 取 arguments、`:151` 过滤出“不属于任何组的非 eager 选项”；`:148` 先取解析期攒下的 usage 错误（非 UsageError 立刻抛，见 `:184`），`:153` 调 `finalizeParameters` 完成赋值，`:157` 把解析期错误与终局化错误合并后一次性抛出，`:159` 才执行全部 postValidate。
13. `finalizeParameters`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:29`）按固定顺序构造待终局化列表：**先 arguments（`:58`），再非组 options（`:59`），最后 groups（`:60`）**；其中“被调用过的参数”排在“未被调用的参数”之前（注释 `:37`），选项是否属于某组由 `Option.group` 扩展判定（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Util.kt:9`）。
14. 列表交给 `iterateFinalization`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:78`），逐项在 `:90`/`:91`/`:92` 调对应参数的 `finalize`。普通选项（不 eager、不属于组）走到 `OptionWithValuesImpl.finalize`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:190`）：先 `getFinalValue`（`:191`，按命令行 → envvar/valueSource → 空列表的顺序取源，逻辑见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/Option.kt:143`）、校验 nvalues（`:194`，不符抛 `IncorrectOptionValueCount`，`:195`）、逐值转换，最后在 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:200` 执行 **`value = transformAll(...)`——这就是该选项 value 真正落定的那一行（终点 A）**。底层存储是 `NullableLateinit`（字段声明 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:180`）；终局化前读它会抛 `LateinitException`，见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/internal/NullableLateinit.kt:18`。
15. 组内选项并不在第 13 步的“非组选项”里，而是组自己终局化：`OptionGroup.finalize`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/groups/ParameterGroup.kt:93`）调 `finalizeOptions`（`:97`），后者同样汇入 `iterateFinalization`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:13`）。
16. `iterateFinalization` 的跨参数依赖机制（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:86`）：某个参数的 `transformAll`/默认值里若读到尚未落定的参数，`NullableLateinit.getValue` 抛 `LateinitException`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/internal/NullableLateinit.kt:18`），被 `:94` 捕获后放入 `nextRound`（`:95`），下一轮重试；`UsageError` 被攒进列表（`:96`-`:99`），`Abort` 仅在此前无错误时重抛（`:100`-`:102`）。收敛条件在 `:105`：`nextRound` 为空，或待重试数量没有减少（即存在互相依赖的环）时停轮。
17. `finalizeCommand` 的收尾（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:161`）：若本命令有子命令但 argv 没指向任何子命令、且 `invokeWithoutSubcommand=false`，抛 `PrintHelpMessage(error=true)`（`:165`）；否则把被调用子命令记入 context（`:168`）。

### 4. 校验阶段（终点 B：validate { } 回调被调用）

18. 所有参数都终局化、且 `(usageErrors + finalizationErrors).throwErrors()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:157`）没有抛出之后，`validateParameters`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:112`）才被调用（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:159`）。其顺序与终局化不同：**先非组 options（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:119`），再 groups（`:122`），最后 arguments（`:125`）**；每个 `postValidate` 抛出的 `UsageError` 由 `gatherErrors` 攒下（`:131`-`:142`），最后同样经 `throwErrors`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Util.kt:11`）汇总抛出。
19. 普通选项的 postValidate 是 `OptionWithValuesImpl.postValidate`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:215`），其中 **`transformValidator(OptionTransformContext(this, context), value)`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:216`）就是 `validate { }` 挂上的校验函数被调用的那一行（终点 B）**。`validate` 扩展把用户 lambda 包成 `{ if (it != null) validator(it) }` 装进 `transformValidator`，见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/Validate.kt:25`。组内选项由 `OptionGroup.postValidate` 逐个转发（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/groups/ParameterGroup.kt:100`），argument 的对应实现见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/arguments/Argument.kt:208`。

### 5. 带子命令时链路在命令树上的展开

- 解析是“先深度递归建树、但父层解析错误会剪断子树”：`parseCommandArgv` 的 while 循环（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:28`）逐层递归，父层是否交出子命令由 `makeResult` 的 `subcommand.takeIf { errors.isEmpty() }`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:189`）决定。父层一旦有解析期错误（如未知选项），子层整棵子树不会被解析。
- 终局化是“两遍扁平序列”（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:109`-`:132`）：**第一遍按根→叶对每个 invocation 跑 `finalizeEagerOptions`**，所以叶子上的 `--help` 会在任何父命令必填校验之前触发；第二遍按根→叶边 `finalizeCommand` 边把命令交给 `run`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:90`-`:94`），即父命令先终局化、先 `run`，再轮到子命令——这与 `CoreCliktCommand.run` 的文档一致（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/CoreCliktCommand.kt:62`）。
- 因此 `sub --help` 这类输入，父命令上的 required 选项（缺失属于**终局化**错误，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/TransformAll.kt:106`）不会挡住子命令 eager `--help`；而父命令上的**解析期**错误会直接把 `sub` 子树剪掉（见第三部分风险点 1）。

## 第二部分：不变量

### 不变量 1：参数的最终值只有在自身 `finalize` 完成后才允许被读到；跨参数读取依赖轮次重试

- **断言**：一个参数 delegate 的 value 在它自己的 `finalize` 执行到赋值行之前处于 `UNINITIALIZED`，任何读取（包括别的参数的 `defaultLazy`/`transformAll`）都得到 `LateinitException` 而不是半成品值；同轮尚未轮到的参数同样读不到。
- **保证代码**：`NullableLateinit.getValue` 的未初始化检查 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/internal/NullableLateinit.kt:18`；赋值只发生在选项 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:200` 与 argument `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/arguments/Argument.kt:205`；轮次捕获与重试在 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:94`（捕获 `LateinitException`）与 `:105`（收敛）。接口契约也明示“finalize 期间不能引用其他参数”，见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/Option.kt:77`。
- **外部观察**：依赖方向与终局化顺序一致时（被依赖项排在前面、或更早一轮落定）正常取值；形成环（两个 `defaultLazy` 互相引用）时不会得到 `null`，而是终局化在 `:105` 收敛后留下未赋值 delegate，随后 postValidate 默认校验器在 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:216` 读 value，向外抛 `IllegalStateException("Cannot read from option delegate before parsing command line")`（`FinalizationOrderTest.default_lazy_referencing_defaulted_option_works_in_either_declaration_order` 与互引用用例分别钉住两种结果）。

### 不变量 2：`UsageError` 被攒起批量抛；非 Usage 的控制流异常立即抛

- **断言**：解析阶段的所有 `UsageError`（未知选项、多余参数等）只收集不抛出；终局化阶段单个参数抛出的 `UsageError` 也只收集，最终用 `MultiUsageError` 批量上报。非 `UsageError`（如 printHelpOnEmptyArgs 时塞入的 `PrintHelpMessage`、completion 消息）不走收集通道，而是立刻抛出。
- **保证代码**：解析期收集 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:208`；终局化收集 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:96`；postValidate 收集 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:138`；非 Usage 立即抛见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:188`（`getUsageErrorsOrThrow`），completion 立即抛见 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:204`；合并出口 `throwErrors` → `MultiUsageError.buildOrNull` 分别在 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Util.kt:12` 与 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/exceptions.kt:150`。
- **外部观察**：一条 argv 里同时有多处用法错误时，上游一次收到多条错误（`MultiUsageError`，消息用换行拼接，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/exceptions.kt:164`）；而 `--help`、空参数帮助、completion 等会立即短路，不会被错误批量“盖住”。

### 不变量 3：解析期错误与终局化错误在 `finalizeCommand` 合流，且终局化错误必须先于 postValidate 抛出

- **断言**：token 扫描攒在 invocation 上的错误与参数赋值/转换阶段产生的错误，在同一份列表 `usageErrors + finalizationErrors` 里一次性抛出；只要这份合并列表非空，任何 `validate { }` 都不会执行。
- **保证代码**：解析期错误取自 `invocation.getUsageErrorsOrThrow()`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:148`），终局化错误由 `finalizeParameters` 返回（`:153`-`:155`），合流与抛出在 `:157`，postValidate 排在 `:159`（在它之后）。
- **外部观察**：既有“未知选项”又有“必填缺失/转换失败”时，用户在同一个 `MultiUsageError` 里同时看到两类问题；但若终局化本身失败，`validate { }` 不会被调用——校验函数永远只面对“所有存活参数都已落定”的状态（eager 选项例外：只对被调用者校验，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:128`）。

### 不变量 4：`Context.errorEncountered` 由三处写入、两处读取，作用是抑制后续交互与批量流程中的 Abort 扩散

- **断言**：只要解析或终局化过程中出现过（被收集的）用法错误，该 context 的 `errorEncountered` 就永久为 true（单次 parse 内）；它改变两件事：(a) `prompt()` 不再发起交互输入而是抛 `Abort()`；(b) 终局化循环里后续参数抛出的 `Abort` 被吞掉而不是中断整个批量收集。
- **保证代码**：标志位声明 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/Context.kt:143`；写入点为 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:187`（makeResult 汇总）、`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:210`（单条选项解析错误）、`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:99`（终局化 UsageError）与 `:141`（postValidate UsageError）；读取点为 prompt 短路 `clikt-mordant/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/PromptOptions.kt:47`，以及 Abort 条件重抛 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:102`。
- **外部观察**：argv 已有错误时，未提供的 `prompt()` 选项不会挂起等待 stdin，而是安静跳过并把已有错误照常批量回吐；没有前序错误时，prompt 取消（Abort）会直接终止本次 parse（exit code 1，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/exceptions.kt:90`）。

### 不变量 5：eager 选项全树先行；非 eager 参数的终局化与校验各自有固定相对顺序

- **断言**：(a) 任意深度子命令上被调用的 eager 选项，在整棵树所有非 eager 参数终局化之前完成；(b) 同一命令内，终局化顺序恒为 arguments → 非组 options → groups（组内 options 在组终局化时处理），而 postValidate 顺序恒为 非组 options → groups → arguments；(c) 终局化时“被调用过的参数”整体排在“未被调用的参数”之前。
- **保证代码**：eager 全树预扫 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:127`；终局化列表顺序 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:57`-`:61`；被调用优先注释与分组 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:37`-`:46`；postValidate 顺序 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:119`-`:125`；eager 只校验被调用者 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/CommandLineParser.kt:128`。
- **外部观察**：`parent`（带 `required()` 选项）+ `sub --help` 最终抛 `PrintHelpMessage(statusCode=0)` 而不是 `MissingOption`；同一命令中 argument、游离 option、组内 option 的 `transformAll` 与 `validate` 回调按上述固定次序触发（由 `FinalizationOrderTest` 的两个顺序用例钉死）。

### 不变量 6：解析与终局化之间不做语义校验，token 层错误不阻断同层后续 token 收集

- **断言**：token 扫描阶段不做类型转换、不查必填、不判多值选项是否凑齐；一个选项带来的解析错误不会中止同一 `CommandParser` 对后续 token 的扫描。
- **保证代码**：`parseOptValues` 的“不足留到 finalization”注释 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:307`；值数校验推迟到 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:194`；`consumeOptionParse` 只记账不抛出 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:205`-`:213`。
- **外部观察**：一条错误 argv 后面对其他选项/参数的使用仍然被记录并可能继续贡献错误或值，最终以批量错误呈现；但要注意它对“子命令是否继续解析”的影响是绝对的（见风险点 1）。

## 第三部分：风险点

三个风险都来自真实可复现行为；复现均为 `CoreCliktCommand` 子类 + 具体 argv，结果是当前 checkout 上实际跑到的。

### 风险点 1（`parsers/ParserInternals.kt`）：父层任何 token 级错误都会静默剪掉子命令子树，吞掉子命令上的 eager `--help`

- **位置**：`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:189`（`subcommand.takeIf { errors.isEmpty() }`），配合递归入口 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:28`。
- **为什么是风险**：父命令的**必填缺失**（终局化错误）不会挡子命令 `--help`（eager 全树预扫，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/Invocation.kt:127`），但父命令的**解析期**错误（哪怕是可恢复的用法错误）会让父层根本不交出 `nextCommand`，于是子命令那一段 argv 完全不被解析，eager 预扫也无从到达叶子。同一个“父有错 + 子请求帮助”的场景，错误发生阶段不同，用户看到的是退出码 1 的用法错误还是退出码 0 的帮助，完全取决于错误落在哪一阶段。改动这一行的 gating 条件（例如为继续解析子树而放宽它）也会静默改变帮助优先的既有语义。
- **最小复现**：

```kotlin
class Sub : CoreCliktCommand() {
    override fun run() {}
}
class Parent : CoreCliktCommand() {
    init { subcommands(Sub()) }
    override fun run() {}
}

Parent().parse(listOf("--bogus", "sub", "--help"))
```

- **实际观察**：抛 `NoSuchOption`，`statusCode=1`，`sub --help` 没有任何效果（子命令既不解析也不打印帮助）；而把 argv 换成 `listOf("sub", "--help")` 时抛的是 `PrintHelpMessage`、`statusCode=0`。
- **最小修法**：不要用本层 `errors` 是否为空作为是否继续解析子树的唯一条件——让 eager 预扫仍能沿 `subcommandInvocations` 到达叶子（例如建树时保留 subcommand、把错误挂在 invocation 上），只在没有 eager 选项短路时才由 `finalizeCommand` 回吐父层用法错误。

### 风险点 2（`core/exceptions.kt`）：`MultiUsageError` 折叠时按错误在列表中的先后，静默丢弃未知选项之后的 `NoSuchArgument`

- **位置**：`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/core/exceptions.kt:156`-`:161`（`buildOrNull` 里 `encounteredUnknownOpt` 一旦见到 `NoSuchOption`，就过滤掉其后所有 `NoSuchArgument`）。
- **为什么是风险**：这个过滤的本意是“多余位置参数可能本应属于那个未知选项”，但它对顺序敏感且不区分这些位置参数是否真的能归属给未知选项。当未知选项**不接受位置值**（或多余参数数量明确超过任何可能的归属）时，独立的“多余参数”错误被整类吞掉，用户只看到第一条错误，修掉它之后才会冒出下一条——与“批量回吐”的设计目标相悖。过滤条件一旦被改动（放宽或去掉），面向同一条 argv 的报错条数与文案会静默变化。
- **最小复现**：

```kotlin
class C : CoreCliktCommand() {
    override fun run() {}
}

C().parse(listOf("--bogus", "extra1", "extra2"))
```

- **实际观察**：抛 `MultiUsageError`，但其 `errors` 里只有 1 条 `NoSuchOption`（消息 `no such option --bogus`）；`handleExcessArgs`（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parsers/ParserInternals.kt:355`）明明为两个多余 token 生成了 `NoSuchArgument`，却在折叠时被丢弃。
- **最小修法**：收窄过滤条件——仅当多余位置参数在 token 流上紧随该未知选项、且数量能被其可能的 arity 解释时才抑制；或至少按命令声明的参数个数保留明确多余的那些 `NoSuchArgument`。

### 风险点 3（`internal/Finalization.kt`）：`defaultLazy` 互相引用成环时，终局化静默收敛，对外抛的是误导性的“解析前读取”内部异常

- **位置**：收敛条件 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/internal/Finalization.kt:105`（`nextRound.isEmpty() || currentRound.size <= nextRound.size` 时 break），与随后默认校验器读取未赋值 delegate 的 `clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:216`。
- **为什么是风险**：环上的参数每轮都抛 `LateinitException`、每轮都整体进 `nextRound`，数量不减少于是循环退出，但既不报错也不赋值；delegate 永远停在 `UNINITIALIZED`。接着 postValidate 里那个“什么都不做”的默认 validator（`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/options/OptionWithValues.kt:318`）在读 `value` 时抛出内部文案 `Cannot read from option delegate before parsing command line`——可此刻明明已经在解析过程中，错误信息会把排查方向带偏（看起来像用户在 parse 前手动读了属性）。更隐蔽的是：非环的跨参数默认值是否可用，取决于声明/调用顺序是否恰好让被依赖项先落定（见不变量 1 与测试第四项），后续若有人调整 `:57`-`:61` 的参数排列或 `:105` 的收敛判定，这类默认值会在不报错的情况下改变取值。
- **最小复现**：

```kotlin
class C : CoreCliktCommand() {
    val a: String by option().defaultLazy { b }
    val b: String by option().defaultLazy { a }
    override fun run() {}
}

C().parse(emptyList())
```

- **实际观察**：`parse` 抛 `IllegalStateException`（具体是内部类型 `LateinitException`，`clikt/src/commonMain/kotlin/com/github/ajalt/clikt/parameters/internal/NullableLateinit.kt:33`），消息为 `Cannot read from option delegate before parsing command line`；没有任何“默认值循环依赖”的提示。
- **最小修法**：在 `:105` 因“数量未减少”而退出时，把仍留在 `nextRound` 的参数转换成一个明确的 `UsageError`（例如“参数默认值存在循环依赖”），而不是让未赋值 delegate 流到 postValidate 再抛内部文案。
