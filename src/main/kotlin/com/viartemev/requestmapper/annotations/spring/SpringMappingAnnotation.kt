package com.viartemev.requestmapper.annotations.spring

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.viartemev.requestmapper.RequestMappingItem
import com.viartemev.requestmapper.annotations.MappingAnnotation
import com.viartemev.requestmapper.annotations.spring.extraction.BasePsiAnnotationValueVisitor
import com.viartemev.requestmapper.annotations.spring.extraction.PsiAnnotationMemberValueExtractor
import com.viartemev.requestmapper.annotations.spring.extraction.PsiArrayInitializerMemberValueExtractor
import com.viartemev.requestmapper.annotations.spring.extraction.PsiReferenceExpressionExtractor
import com.viartemev.requestmapper.utils.containsCurlyBrackets
import com.viartemev.requestmapper.utils.fetchAnnotatedElement
import com.viartemev.requestmapper.utils.unquote

abstract class SpringMappingAnnotation(val psiAnnotation: PsiAnnotation) : MappingAnnotation {

    override fun values(): List<RequestMappingItem> =
            fetchRequestMappingItem(psiAnnotation, psiAnnotation.fetchAnnotatedElement() as PsiMethod, extractMethod())

    abstract fun extractMethod(): String

    private fun fetchRequestMappingItem(annotation: PsiAnnotation, psiMethod: PsiMethod, methodName: String): List<RequestMappingItem> {
        val classMappings = fetchRequestMappingAnnotationsFromParentClass(psiMethod)
        val methodMappings = fetchMethodMapping(annotation, psiMethod)
        val paramsMappings = fetchParamMapping(annotation)
        return classMappings.map { clazz ->
            methodMappings.map {
                paramsMappings.map { param ->
                    val urlPath = if (clazz.isBlank() && it.isBlank()) "/" else "$clazz$it${if (param.isNotBlank()) " params=$param" else ""}"
                    RequestMappingItem(psiMethod, urlPath, methodName)
                }
            }.flatten()
        }.flatten()
    }

    private fun fetchParamMapping(annotation: PsiAnnotation): List<String> {
        val fetchMappingsFromAnnotation = fetchMappingsFromAnnotation(annotation, PARAMS)
        return if (fetchMappingsFromAnnotation.isNotEmpty()) fetchMappingsFromAnnotation else listOf("")
    }

    private fun fetchRequestMappingAnnotationsFromParentClass(psiMethod: PsiMethod): List<String> {
        val classMapping = psiMethod
                .containingClass
                ?.modifierList
                ?.annotations
                ?.filterNotNull()
                ?.filter { it.qualifiedName == SPRING_REQUEST_MAPPING_CLASS }
                ?.flatMap { fetchMapping(it) } ?: emptyList()
        return if (classMapping.isEmpty()) listOf("") else classMapping
    }

    private fun fetchMapping(annotation: PsiAnnotation): List<String> {
        val pathMapping = fetchMappingsFromAnnotation(annotation, PATH)
        return if (!pathMapping.isEmpty()) pathMapping else {
            val valueMapping = fetchMappingsFromAnnotation(annotation, VALUE)
            if (valueMapping.isNotEmpty()) valueMapping else listOf("")
        }
    }

    private fun fetchMethodMapping(annotation: PsiAnnotation, method: PsiMethod): List<String> {
        val pathMethodMapping = fetchMapping(annotation)

        if (pathMethodMapping.any { it.containsCurlyBrackets() }) {
            return addPathVariablesTypes(method, pathMethodMapping.toMutableList())
        }

        return pathMethodMapping
    }

    private fun addPathVariablesTypes(method: PsiMethod, pathMethodMapping: MutableList<String>): List<String> {
        val parametersNameWithType = method
                .parameterList
                .parameters
                .mapNotNull { extractParameterNameWithType(it) }
                .toMap()

        parametersNameWithType.forEach { parameter ->
            pathMethodMapping.replaceAll { it.replace("{${parameter.key}}", "{${parameter.value}:${parameter.key}}") }
        }

        return pathMethodMapping.toList()
    }

    private fun extractParameterNameWithType(parameter: PsiParameter): Pair<String, String>? {
        val parameterType = parameter.type.presentableText
        val pathVariableAnnotations = parameter
                .modifierList
                ?.annotations
                ?.filter { it.qualifiedName == SPRING_PATH_VARIABLE_CLASS }

        return if (pathVariableAnnotations == null || pathVariableAnnotations.isEmpty()) {
            null
        } else {
            pathVariableAnnotations
                    .map {
                        Pair((extractParameterNameFromAnnotation(it)
                                ?: parameter.name!!).unquote(), parameterType.unquote())
                    }
                    //only one PathVariable annotation possible
                    .first()
        }
    }

    private fun extractParameterNameFromAnnotation(annotation: PsiAnnotation): String? {
        val pathVariableValue = annotation.findAttributeValue(VALUE)
        val pathVariableName = annotation.findAttributeValue(NAME)
        return if (pathVariableValue != null && pathVariableValue.text.isNotBlank()) {
            pathVariableValue.text
        } else if (pathVariableName != null && pathVariableName.text.isNotBlank()) {
            pathVariableName.text
        } else {
            null
        }
    }

    private fun fetchMappingsFromAnnotation(annotation: PsiAnnotation, parameter: String): List<String> {
        return object : BasePsiAnnotationValueVisitor() {
            override fun visitPsiArrayInitializerMemberValue(arrayAValue: PsiArrayInitializerMemberValue) =
                    PsiArrayInitializerMemberValueExtractor().extract(arrayAValue)

            override fun visitPsiReferenceExpression(expression: PsiReferenceExpression) =
                    PsiReferenceExpressionExtractor().extract(expression)

            override fun visitPsiAnnotationMemberValue(value: PsiAnnotationMemberValue) =
                    PsiAnnotationMemberValueExtractor().extract(value)
        }.visit(annotation, parameter)
    }

    companion object {
        private const val VALUE = "value"
        private const val PATH = "path"
        private const val NAME = "name"
        private const val PARAMS = "params"
        private const val SPRING_REQUEST_MAPPING_CLASS = "org.springframework.web.bind.annotation.RequestMapping"
        private const val SPRING_PATH_VARIABLE_CLASS = "org.springframework.web.bind.annotation.PathVariable"
    }
}