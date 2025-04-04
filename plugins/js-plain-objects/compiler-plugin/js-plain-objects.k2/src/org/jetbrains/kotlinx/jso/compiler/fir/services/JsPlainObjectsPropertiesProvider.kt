/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.jspo.compiler.fir.services

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.createCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.caches.getValue
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.withReplacedConeType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlinx.jspo.compiler.fir.JsPlainObjectsPredicates
import org.jetbrains.kotlinx.jspo.compiler.resolve.JsPlainObjectsAnnotations

data class ClassProperty(val name: Name, val resolvedTypeRef: FirResolvedTypeRef)

class JsPlainObjectsPropertiesProvider(session: FirSession) : FirExtensionSessionComponent(session) {

    private val cache: FirCache<FirClassSymbol<*>, List<ClassProperty>, Nothing?> =
        session.firCachesFactory.createCache(this::createJsPlainObjectProperties)

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(JsPlainObjectsPredicates.AnnotatedWithJsPlainObject.DECLARATION)
    }

    fun getJsPlainObjectsPropertiesForClass(classSymbol: FirClassSymbol<*>): List<ClassProperty> {
        return cache.getValue(classSymbol)
    }

    private fun createJsPlainObjectProperties(classSymbol: FirClassSymbol<*>): List<ClassProperty> =
        if (!classSymbol.hasAnnotation(JsPlainObjectsAnnotations.jsPlainObjectAnnotationClassId, session)) {
            emptyList()
        } else {
            buildList {
                classSymbol.resolvedSuperTypes.forEach {
                    val expandedType = it.fullyExpandedType(session)
                    val superInterface = expandedType
                        .toRegularClassSymbol(session)
                        ?.takeIf { it.classKind == ClassKind.INTERFACE } ?: return@forEach

                    val substitutionMap = superInterface.typeParameterSymbols
                        .zip(expandedType.typeArguments)
                        .associate { (declared, provided) -> declared to provided.type!! }

                    val substitutor = substitutorByMap(substitutionMap, session)

                    val superInterfaceSimpleObjectProperties = createJsPlainObjectProperties(superInterface)
                    superInterfaceSimpleObjectProperties.forEach {
                        add(
                            ClassProperty(
                                it.name,
                                it.resolvedTypeRef.withReplacedConeType(substitutor.substituteOrNull(it.resolvedTypeRef.coneType))
                            )
                        )
                    }
                }

                classSymbol
                    .declaredMemberScope(session, null)
                    .processAllProperties {
                        if (it.visibility == Visibilities.Public && !it.isOverride && it is FirPropertySymbol) {
                            addIfNotNull(ClassProperty(it.name, it.resolvedReturnTypeRef))
                        }
                    }
            }
        }
}

val FirSession.jsPlainObjectPropertiesProvider: JsPlainObjectsPropertiesProvider by FirSession.sessionComponentAccessor()
