/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.utils.atMostOne
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.isSingleFieldValueClass
import org.jetbrains.kotlin.ir.descriptors.IrBuiltinOperatorDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * This lowering pass lowers some calls to [IrBuiltinOperatorDescriptor]s.
 */
internal class BuiltinOperatorLowering(val context: Context) : FileLoweringPass, IrBuildingTransformer(context) {

    private val irBuiltins = context.irBuiltIns
    private val symbols = context.symbols

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        return when (expression.symbol) {
            irBuiltins.eqeqSymbol, in ieee754EqualsSymbols -> lowerEqeq(expression)

            irBuiltins.eqeqeqSymbol -> lowerEqeqeq(expression)

            irBuiltins.checkNotNullSymbol -> lowerCheckNotNull(expression)

            irBuiltins.noWhenBranchMatchedExceptionSymbol -> IrCallImpl.fromSymbolOwner(
                    expression.startOffset, expression.endOffset,
                    context.symbols.throwNoWhenBranchMatchedException.owner.returnType,
                    context.symbols.throwNoWhenBranchMatchedException)

            irBuiltins.linkageErrorSymbol -> with(symbols.throwIrLinkageError) { irCall(expression, this, newReturnType = owner.returnType) }

            else -> expression
        }
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        expression.transformChildrenVoid(this)
        if (expression.argument.type.isNothing()) {
            return expression.argument
        }
        return expression
    }

    private val ieee754EqualsSymbols: Set<IrSimpleFunctionSymbol> =
            irBuiltins.ieee754equalsFunByOperandType.values.toSet()

    private fun lowerEqeqeq(expression: IrCall): IrExpression {
        val lhs = expression.arguments[0]!!
        val rhs = expression.arguments[1]!!

        return if (lhs.type.isInlinedNative() && rhs.type.isInlinedNative()) {
            // Achieve the same behavior as with JVM BE: if both sides of `===` are values, then compare by value:
            lowerEqeq(expression)
            // Note: such comparisons are deprecated.
        } else {
            expression
        }
    }

    private fun IrBuilderWithScope.reinterpret(expression: IrExpression, toType: IrType) =
            reinterpret(expression, expression.type, toType)

    private fun IrBuilderWithScope.reinterpret(expression: IrExpression, fromType: IrType, toType: IrType) =
            irCallWithSubstitutedType(symbols.reinterpret.owner, listOf(fromType, toType)).apply {
                arguments[0] = expression
            }

    private val anyEquals = irBuiltins.anyClass.owner.simpleFunctions().single { it.name.asString() == "equals" }

    private fun lowerEqeq(expression: IrCall): IrExpression {
        // TODO: optimize boxing?

        builder.at(expression).run {
            val lhs = expression.arguments[0]!!
            val rhs = expression.arguments[1]!!

            if (rhs.isNullConst()) {
                return irEqeqNull(lhs)
            }

            if (lhs.isNullConst()) {
                return irEqeqNull(rhs)
            }

            if (expression.symbol == irBuiltins.eqeqSymbol) {
                lhs.type.getInlinedClassNative()?.let {
                    if (it == rhs.type.getInlinedClassNative() && inlinedClassHasDefaultEquals(it)) {
                        return genInlineClassEquals(expression.symbol, rhs, lhs)
                    }
                }
            }

            return genFloatingOrReferenceEquals(expression.symbol, lhs, rhs)
        }
    }

    private fun inlinedClassHasDefaultEquals(irClass: IrClass): Boolean {
        if (!irClass.isSingleFieldValueClass) {
            // Implicitly-inlined class, e.g. primitive one.
            return true
        }

        val equals = irClass.simpleFunctions()
                .single { it.name.asString() == "equals" && it.parameters.size == 2 && it.overrides(anyEquals) }

        return equals.origin == IrDeclarationOrigin.GENERATED_SINGLE_FIELD_VALUE_CLASS_MEMBER
    }

    fun IrBuilderWithScope.genInlineClassEquals(
            symbol: IrFunctionSymbol,
            rhs: IrExpression,
            lhs: IrExpression
    ): IrExpression {
        val lhsBinaryType = lhs.type.computeBinaryType()
        return when (lhsBinaryType) {
            is BinaryType.Primitive -> {
                val areEqualByValue = symbols.areEqualByValue[lhsBinaryType.type]!!.owner
                irCall(areEqualByValue).apply {
                    arguments[0] = reinterpret(lhs, areEqualByValue.parameters[0].type)
                    arguments[1] = reinterpret(rhs, areEqualByValue.parameters[1].type)
                }
            }

            is BinaryType.Reference -> {
                // TODO: don't use binaryType.nullable.
                val lhsRawType = if (lhsBinaryType.nullable) irBuiltins.anyNType else irBuiltins.anyType
                val rhsBinaryType = rhs.type.computeBinaryType() as BinaryType.Reference<*>
                val rhsRawType = if (rhsBinaryType.nullable) irBuiltins.anyNType else irBuiltins.anyType

                genFloatingOrReferenceEquals(
                        symbol,
                        reinterpret(lhs, lhsRawType),
                        reinterpret(rhs, rhsRawType)
                )
            }
        }
    }

    private fun IrBuilderWithScope.irEqeqNull(expression: IrExpression): IrExpression {
        val type = expression.type.makeNullable()
        return when (val primitiveBinaryTypeOrNull = type.computePrimitiveBinaryTypeOrNull()) {
            null -> irEqeqeq(reinterpret(expression, type, irBuiltins.anyNType), irNull())
            PrimitiveBinaryType.POINTER -> irCall(symbols.areEqualByValue[PrimitiveBinaryType.POINTER]!!.owner).apply {
                arguments[0] = reinterpret(expression, type, symbols.nativePtrType)
                arguments[1] = reinterpret(irNull(), type, symbols.nativePtrType)
            }
            else -> error("Nullable type ${type.render()} is $primitiveBinaryTypeOrNull")
        }
    }

    private fun lowerCheckNotNull(expression: IrCall) = builder.at(expression).irBlock(resultType = expression.type) {
        val temp = irTemporary(expression.arguments[0]!!)
        +irIfThen(context.irBuiltIns.unitType, irEqeqNull(irGet(temp)), irCall(symbols.throwNullPointerException))
        +irGet(temp)
    }

    private fun IrBuilderWithScope.irLogicalAnd(lhs: IrExpression, rhs: IrExpression) = context.andand(lhs, rhs)
    private fun IrBuilderWithScope.irIsNull(exp: IrExpression) = irEqeqeq(exp, irNull())
    private fun IrBuilderWithScope.irIsNotNull(exp: IrExpression) = irNot(irEqeqeq(exp, irNull()))

    private fun IrBuilderWithScope.genFloatingOrReferenceEquals(symbol: IrFunctionSymbol, lhs: IrExpression, rhs: IrExpression): IrExpression {
        // TODO: areEqualByValue and ieee754Equals intrinsics are specially treated by code generator
        // and thus can be declared synthetically in the compiler instead of explicitly in the runtime.
        fun callEquals(lhs: IrExpression, rhs: IrExpression): IrExpression {
            if (symbol in ieee754EqualsSymbols) {
                // Find a type-compatible `konan.internal.ieee754Equals` intrinsic:
                val intrinsic = selectIntrinsic(symbols.ieee754Equals, lhs.type, rhs.type, true)
                // Type of operands may be lost due to erasure on inlining phase
                if (intrinsic != null) {
                    return irCall(intrinsic).apply {
                        arguments[0] = lhs
                        arguments[1] = rhs
                    }
                }
            }
            return irCall(symbols.equals).apply {
                arguments[0] = lhs
                arguments[1] = rhs
            }
        }

        val lhsIsNotNullable = !lhs.type.isNullable()
        val rhsIsNotNullable = !rhs.type.isNullable()

        return if (symbol in ieee754EqualsSymbols) {
            if (lhsIsNotNullable && rhsIsNotNullable)
                callEquals(lhs, rhs)
            else irBlock {
                val lhsTemp = irTemporary(lhs)
                val rhsTemp = irTemporary(rhs)
                if (lhsIsNotNullable xor rhsIsNotNullable) { // Exactly one nullable.
                    +irLogicalAnd(
                            irIsNotNull(irGet(if (lhsIsNotNullable) rhsTemp else lhsTemp)),
                            callEquals(irGet(lhsTemp), irGet(rhsTemp))
                    )
                } else { // Both are nullable.
                    +irIfThenElse(context.irBuiltIns.booleanType, irIsNull(irGet(lhsTemp)),
                            irIsNull(irGet(rhsTemp)),
                            irLogicalAnd(
                                    irIsNotNull(irGet(rhsTemp)),
                                    callEquals(irGet(lhsTemp), irGet(rhsTemp))
                            )
                    )
                }
            }
        } else {
            if (lhsIsNotNullable)
                callEquals(lhs, rhs)
            else {
                irBlock {
                    val lhsTemp = irTemporary(lhs)
                    if (rhsIsNotNullable)
                        +irLogicalAnd(irIsNotNull(irGet(lhsTemp)), callEquals(irGet(lhsTemp), rhs))
                    else {
                        val rhsTemp = irTemporary(rhs)
                        +irIfThenElse(irBuiltins.booleanType, irIsNull(irGet(lhsTemp)),
                                irIsNull(irGet(rhsTemp)),
                                callEquals(irGet(lhsTemp), irGet(rhsTemp))
                        )
                    }
                }
            }
        }
    }

    private fun selectIntrinsic(from: List<IrSimpleFunctionSymbol>, lhsType: IrType, rhsType: IrType, allowNullable: Boolean) =
            from.atMostOne {
                val leftParamType = it.owner.parameters[0].type
                val rightParamType = it.owner.parameters[1].type
                (lhsType.isSubtypeOf(leftParamType, context.typeSystem) || (allowNullable && lhsType.isSubtypeOf(leftParamType.makeNullable(), context.typeSystem)))
                        && (rhsType.isSubtypeOf(rightParamType, context.typeSystem) || (allowNullable && rhsType.isSubtypeOf(rightParamType.makeNullable(), context.typeSystem)))
            }
}
