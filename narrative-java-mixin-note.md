# narrative-java: exempt Mixin syntax from NAR130, NAR131 and NAR106

From the gtnh-speedrun-audit pass on 2026-10-18 (skill commit 9ffb5f7). The user decided to keep standard
Mixin syntax in this repo, and asked for this note so that the skill stops flagging it.

## What fired

`verify.py` reported 82 findings that are Mixin syntax, not style faults. 77 are in the 21 mixin classes and
5 are in three duck interfaces:

| Code | Count | Example | Why the name or shape is fixed |
|---|---|---|---|
| NAR130 | 64 | `TEAltarMixin.java:29` `@Unique private int audit$tierBefore` | the mod-id prefix on a member that a mixin adds to the target |
| NAR130 | 5 | `GtExtraHatches.java:13` `List<? extends MTEHatch> audit$extraEnergyHatches()` | a duck interface member, public in the target |
| NAR130 | 3 | `ParticipantInfoMixin.java:25` `@Shadow ... PLAYER` | a `@Shadow` member has the target's name |
| NAR131 | 9 | `StructureUtilityMixin.java:37` `@Redirect ... audit$visited(World world, int x, int y, int z)` | the injector's signature is fixed by the target method |
| NAR106 | 1 | `StructureUtilityMixin.java:26` `public abstract class StructureUtilityMixin` | a mixin class is never instantiated |

## Why each one is correct Mixin code

**The `modid$` prefix.** A mixin merges its members into the target class. When two mods add a member of
the same name to one class, the members clash. The advice in the Fabric wiki's modding tips is to
"prefix them with your modid and a separator such as $ or _"
([source](https://wiki.fabricmc.net/tutorial:modding_tips)). The advice covers added fields and non-static
accessor methods. `@Unique` renames a non-public method on a clash. For a public method, the Mixin javadoc
of `Unique` has the method "discarded if a matching target exists". A duck interface method must be
public, so on a duck method only the prefix prevents a clash. With the `$`, a reader can also find a merged
member in a stack trace from the target class. This repo has 56 distinct `audit$` names.

**`@Shadow` names.** Mixin binds a `@Shadow` field or method to the target member by name. A rename
breaks the mixin at apply time. The name is whatever the target uses, such as the BetterQuesting
constant `PLAYER`.

**Injector signatures.** An `@Inject` handler takes the target method's parameters, then `CallbackInfo`
or `CallbackInfoReturnable`, then any captured locals. A `@Redirect` handler takes the receiver and the
redirected call's arguments. Mixin checks the descriptor, so a parameter record isn't possible. This is the
same case as an `@Override`, which `ParameterNumber` already skips with `ignoreOverriddenMethods`.

**Abstract mixin classes.** A mixin class is never instantiated. With `abstract`, the class can declare
`@Shadow` abstract methods and implement an interface of the target without bodies. This isn't
inheritance in the sense of J1-P18, and the class has no subclass.

## Proposed change

1. Checkstyle (`checkstyle.xml`): add a `SuppressionXpathSingleFilter` for `MethodName`, `MemberName` and
   `StaticVariableName` on a member of a class annotated `@Mixin` when the member is annotated `@Shadow`,
   or its name matches `^[a-z][a-z0-9_]*\$[a-z][a-zA-Z0-9]*$`. Exempt the same prefixed names on an
   interface that a `@Mixin` class implements. That interface is hard to find from the interface file, so a
   plain name-pattern exemption for `^[a-z][a-z0-9_]*\$` method names may be the practical rule. Add a
   filter for `ParameterNumber` on a `METHOD_DEF` annotated `@Inject`, `@Redirect`, `@ModifyArg`,
   `@ModifyArgs`, `@ModifyVariable`, `@ModifyConstant`, `@WrapOperation` or `@WrapWithCondition`.
2. PMD (`pmd-ruleset.xml`, NAR106): add `[not(pmd-java:hasAnnotation('org.spongepowered.asm.mixin.Mixin'))]`
   to the XPath.
3. SKILL.md, Naming: in a `@Mixin` class, the `modid$` prefix and `@Shadow` names follow Mixin, not the Java
   convention. Types, at the abstract class rule: a `@Mixin` class may be abstract.
4. A fixture pair for each change, one that passes and one that fails. For example, an `audit$` field
   outside a `@Mixin` class still fails NAR130.

## Other skill issues found in the same pass

- **`@Shadow` collection types (NAR140).** A `@Shadow` field has the target field's type, such as GT's
  `ArrayList<MTEHatchEnergy> mEnergyHatches`. In `MTEMultiBlockBaseMixin.java` and `TTMultiblockBaseMixin.java`,
  4 such fields are flagged. The same `@Mixin` exemption applies.
- **A javadoc above a multi-line annotation is missed (NAR116, NAR118).** `lineAboveAnnotations()` in
  `javachecks.py` skips only lines that start with `@`. The continuation lines of `@Mod(...)` or
  `@Redirect(...)` end the skip before the javadoc. `GtnhSpeedrunAudit.java:64` (`@Mod` over 6 lines) and
  `StructureUtilityMixin.java:42` (`@Redirect` over 4 lines) have a javadoc and are still flagged. A fix: skip
  an annotation as a unit by balancing its parentheses.
- **Overloads that call each other (NAR101).** `GtMachineSites.java:20` has three public `readSite` overloads,
  and two of them call the third. The PMD rule matches a caller by method name only, so it flags the first
  overload whatever the order. A fix: match on name and argument count.
- **`MethodHandle.invoke()` declares `throws Throwable` (NAR142).** Code that calls it must catch `Throwable` or
  declare `throws Throwable`. `StructureTiers.java:412` keeps the catch, with the reason in its noqa.
  This case could join the two existing NAR142 exceptions in SKILL.md.

Until that change ships, gtnh-speedrun-audit silences these lines with `// noqa: NAR130`, `NAR131`,
`NAR106` and `NAR140`.
