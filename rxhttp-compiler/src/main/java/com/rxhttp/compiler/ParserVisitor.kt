package com.rxhttp.compiler

import com.rxhttp.compiler.exception.ProcessingException
import com.squareup.javapoet.*
import rxhttp.wrapper.annotation.Parser
import java.util.*
import javax.annotation.processing.Filer
import javax.lang.model.element.*
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Types
import kotlin.collections.ArrayList

class ParserVisitor {

    private val elementMap = LinkedHashMap<String, TypeElement>()
    private val typeMap = LinkedHashMap<String, List<TypeMirror>>()

    fun add(element: TypeElement, types: Types) {
        checkParserValidClass(element, types)
        val annotation = element.getAnnotation(Parser::class.java)
        val name: String = annotation.name
        require(name.isNotEmpty()) {
            String.format(
                "methodName() in @%s for class %s is null or empty! that's not allowed",
                Parser::class.java.simpleName, element.qualifiedName.toString()
            )
        }
        try {
            annotation.wrappers
        } catch (e: MirroredTypesException) {
            val typeMirrors = e.typeMirrors
            typeMap[name] = typeMirrors
        }
        elementMap[name] = element
    }

    fun getMethodList(filer: Filer): List<MethodSpec> {

        val type = ClassName.get("java.lang.reflect", "Type")
        val parameterizedType = ClassName.get("rxhttp.wrapper.entity", "ParameterizedTypeImpl")
        val className = ClassName.get(Class::class.java)

        val methodList = ArrayList<MethodSpec>()
        val rxHttpExtensions = RxHttpExtensions()

        //获取自定义的解析器
        for ((parserAlias, typeElement) in elementMap) {

            //生成RxHttp扩展方法(kotlin编写的方法)
            rxHttpExtensions.generateRxHttpExtendFun(typeElement, parserAlias)

            if (isDependenceRxJava()) { //依赖了RxJava，则生成自定义的asXxx方法
                //onParser方法返回类型
                val returnTypeMirror = getOnParserFunReturnType(typeElement) ?: continue
                val onParserFunReturnType = TypeName.get(returnTypeMirror)

                val typeVariableNames = ArrayList<TypeVariableName>()
                typeElement.typeParameters.forEach {
                    typeVariableNames.add(TypeVariableName.get(it))
                }

                //遍历public构造方法
                typeElement.getPublicConstructorFun().forEach {
                    //根据构造方法参数，获取asXxx方法需要的参数
                    val parameterList = ArrayList<ParameterSpec>()
                    var typeIndex = 0
                    it.parameters.forEach { variableElement ->
                        val variableType = variableElement.asType()
                        if (variableType.toString() == "java.lang.reflect.Type[]") {
                            typeVariableNames.forEach { typeVariableName ->
                                //Type类型参数转Class<T>类型
                                val classTypeName =
                                    ParameterizedTypeName.get(className, typeVariableName)
                                val variableName =
                                    "${typeVariableName.name.lowercase(Locale.getDefault())}Type"
                                val parameterSpec =
                                    ParameterSpec.builder(classTypeName, variableName).build()
                                parameterList.add(parameterSpec)
                            }
                        } else if (variableType.toString() == "java.lang.reflect.Type"
                            && typeIndex < typeVariableNames.size
                        ) {
                            //Type类型参数转Class<T>类型
                            val classTypeName = ParameterizedTypeName.get(
                                className, typeVariableNames[typeIndex++]
                            )
                            val variableName = variableElement.simpleName.toString()
                            val parameterSpec =
                                ParameterSpec.builder(classTypeName, variableName).build()
                            parameterList.add(parameterSpec)
                        } else {
                            parameterList.add(ParameterSpec.get(variableElement))
                        }
                    }

                    //方法名
                    var methodName = "as$parserAlias"
                    //方法体
                    val methodBody =
                        "return asParser(new \$T${getTypeVariableString(typeVariableNames)}(${
                            getParamsName(it.parameters, parameterList, typeVariableNames.size)
                        }))"

                    //生成的as方法返回类型(Observable<T>类型)
                    var asFunReturnType = ParameterizedTypeName.get(
                        getClassName("Observable"), onParserFunReturnType)
                    methodList.add(
                        MethodSpec.methodBuilder(methodName)
                            .addModifiers(Modifier.PUBLIC)
                            .addTypeVariables(typeVariableNames)
                            .addParameters(parameterList)
                            .varargs(it.isVarArgs && parameterList.last().type is ArrayTypeName)
                            .addStatement(methodBody, ClassName.get(typeElement))  //方法里面的表达式
                            .returns(asFunReturnType)
                            .build())

                    var haveClassTypeParam = false
                    parameterList.forEach { p ->
                        if (p.type.toString().startsWith("java.lang.Class")) {
                            haveClassTypeParam = true
                        }
                    }
                    //有泛型且有Class类型参数
                    if (typeVariableNames.size == 1 && haveClassTypeParam) {
                        val wrapperListClass = mutableListOf<ClassName>()
                        if (typeVariableNames.size == 1)
                            wrapperListClass.add(ClassName.get("java.util", "List"))

                        //泛型的包裹类型，取自Parser注解的wrappers字段
                        typeMap[parserAlias]?.forEach { mirror ->
                            val tempClassName = ClassName.bestGuess(mirror.toString())
                            if (!wrapperListClass.contains(tempClassName)) {
                                wrapperListClass.add(tempClassName)
                            }
                        }

                        wrapperListClass.forEach { wrapperClass ->

                            //1、asXxx方法返回值
                            val onParserFunReturnWrapperType = if (onParserFunReturnType is ParameterizedTypeName) {
                                //返回类型有n个泛型，需要对每个泛型再次包装
                                val typeNames = ArrayList<TypeName>()
                                for (typeArg in onParserFunReturnType.typeArguments) {
                                    typeNames.add(ParameterizedTypeName.get(wrapperClass, typeArg))
                                }
                                ParameterizedTypeName.get(onParserFunReturnType.rawType, *typeNames.toTypedArray())
                            } else {
                                ParameterizedTypeName.get(wrapperClass, onParserFunReturnType)
                            }
                            asFunReturnType = ParameterizedTypeName.get(getClassName("Observable"), onParserFunReturnWrapperType)

                            //2、asXxx方法名
                            val name = wrapperClass.toString()
                            val simpleName = name.substring(name.lastIndexOf(".") + 1)
                            methodName = "as$parserAlias${simpleName}"

                            //3、asXxx方法体
                            val funBody = CodeBlock.builder()
                            val paramsName = StringBuilder()
                            //遍历参数，取出参数名
                            parameterList.forEachIndexed { index, param ->
                                if (index > 0) paramsName.append(", ")
                                if (param.type.toString().startsWith("java.lang.Class")) {
                                    /*
                                     * Class类型参数，需要进行再次包装，最后再取参数名
                                     * 格式：Type tTypeList = ParameterizedTypeImpl.get(List.class, tType);
                                     */
                                    val variableName = "${param.name}$simpleName"
                                    val expression =
                                        "\$T $variableName = \$T.get($simpleName.class, ${param.name})"
                                    funBody.addStatement(expression, type, parameterizedType)
                                    val parameterType = it.parameters[index].asType()
                                    if ("java.lang.reflect.Type[]" == parameterType.toString()) {
                                        paramsName.append("new Type[]{$variableName}")
                                    } else {
                                        paramsName.append(variableName)
                                    }
                                } else {
                                    paramsName.append(param.name)
                                }
                            }
                            val returnStatement = "return asParser(new \$T${getTypeVariableString(typeVariableNames)}($paramsName))"
                            funBody.addStatement(returnStatement, ClassName.get(typeElement))

                            //4、生成asXxx方法
                            methodList.add(
                                MethodSpec.methodBuilder(methodName)
                                    .addModifiers(Modifier.PUBLIC)
                                    .addTypeVariables(typeVariableNames)
                                    .addParameters(parameterList)
                                    .varargs(it.isVarArgs && parameterList.last().type is ArrayTypeName)
                                    .addCode(funBody.build())  //方法里面的表达式
                                    .returns(asFunReturnType)
                                    .build())
                        }
                    }
                }
            }
        }
        rxHttpExtensions.generateClassFile(filer)
        return methodList
    }


    /**
     * @param variableElements 解析器构造方法参数列表
     * @param parameterSpecs 通过解析器构造方法参数列表转换而来的实际参数列表，parameterSpecs.size() >= variableElements.size()
     * @param typeCount 解析器泛型数量
     */
    private fun getParamsName(
        variableElements: List<VariableElement>,
        parameterSpecs: MutableList<ParameterSpec>,
        typeCount: Int
    ): String {
        val sb = StringBuilder()
        var paramIndex = 0
        var variableIndex = 0
        val variableSize = variableElements.size
        val paramSize = parameterSpecs.size
        while (paramIndex < paramSize && variableIndex < variableSize) {
            if (variableIndex > 0) sb.append(", ")
            val type = variableElements[variableIndex++].asType()
            if ("java.lang.reflect.Type[]" == type.toString()) {
                sb.append("new Type[]{")
                for (i in 0 until typeCount) {
                    if (i > 0) sb.append(", ")
                    sb.append(parameterSpecs[paramIndex++].name)
                }
                sb.append("}")
            } else
                sb.append(parameterSpecs[paramIndex++].name)
        }
        return sb.toString()
    }


    //获取泛型字符串 比如:<T> 、<K,V>等等
    private fun getTypeVariableString(typeVariableNames: List<TypeVariableName>): String {
        return if(typeVariableNames.isNotEmpty()) "<>" else ""
    }


    //获取onParser方法返回类型
    private fun getOnParserFunReturnType(typeElement: TypeElement): TypeMirror? {
        typeElement.enclosedElements.forEach {
            if (it is ExecutableElement   //是方法
                && it.getModifiers().contains(Modifier.PUBLIC)  //public修饰
                && !it.getModifiers().contains(Modifier.STATIC) //非静态
                && it.simpleName.toString() == "onParse"  //onParse方法
                && it.parameters.size == 1  //只有一个参数
                && TypeName.get(it.parameters[0].asType())
                    .toString() == "okhttp3.Response"  //参数是okhttp3.Response类型
            ) {
                return it.returnType
            }
        }
        return null
    }
}


@Throws(ProcessingException::class)
private fun checkParserValidClass(element: TypeElement, types: Types) {
    if (!element.modifiers.contains(Modifier.PUBLIC)) {
        throw ProcessingException(
            element,
            "The class ${Parser::class.java.simpleName} is not public"
        )
    }
    if (element.modifiers.contains(Modifier.ABSTRACT)) {
        throw ProcessingException(
            element,
            "The class ${element.simpleName} is abstract. You can't annotate abstract classes with @${Parser::class.java.simpleName}"
        )
    }

    val constructorFun = element.getVisibleConstructorFun()
    if (element.typeParameters.size > 0) {
        //有泛型的解析器不能声明为final类型
        if (element.modifiers.contains(Modifier.FINAL)) {
            throw ProcessingException(
                element,
                "This class ${element.simpleName} cannot be declared final"
            )
        }
        //1、查找无参构造方法
        val noArgumentConstructorFun = constructorFun.findNoArgumentConstructorFun()
            ?: throw ProcessingException(
                element,
                "This class must be declared 'protected %${element.simpleName}()' constructor method"
            )
        if (!noArgumentConstructorFun.modifiers.contains(Modifier.PROTECTED)) {
            //无参构造方法必须要声明为protected
            throw ProcessingException(
                element,
                "This class ${element.simpleName} no-argument constructor must be declared protected"
            )
        }

        if (isDependenceRxJava()) {
            //2、如果依赖了RxJava，则需要查找带 java.lang.reflect.Type 参数的构造方法
            val typeArgumentConstructorFun = constructorFun
                .findTypeArgumentConstructorFun(element.typeParameters.size)
            if (typeArgumentConstructorFun == null) {
                val method = StringBuffer("public %s(")
                for (i in element.typeParameters.indices) {
                    method.append("java.lang.reflect.Type")
                    if (i == element.typeParameters.size - 1) {
                        method.append(")")
                    } else method.append(", ")
                }
                throw ProcessingException(
                    element,
                    "This class ${element.simpleName} must declare '$method' constructor method"
                )
            }
        }
    }

    var currentClass = element
    while (true) {
        val interfaces: MutableList<out TypeMirror> = currentClass.interfaces
        //遍历实现的接口有没有Parser接口
        for (typeMirror in interfaces) {
            if (typeMirror.toString().contains("rxhttp.wrapper.parse.Parser")) {
                return
            }
        }
        //未遍历到Parser，则找到父类继续，一直循环下去，直到最顶层的父类
        val superClassType = currentClass.superclass
        if (superClassType.kind == TypeKind.NONE) {
            throw ProcessingException(
                element,
                "The class ${element.qualifiedName} annotated with @${Parser::class.java.simpleName} must inherit from rxhttp.wrapper.parse.Parser<T>"
            )
        }
        //TypeMirror转TypeElement
        currentClass = types.asElement(superClassType) as TypeElement
    }
}