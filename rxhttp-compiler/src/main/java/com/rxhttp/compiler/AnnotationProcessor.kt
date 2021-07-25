package com.rxhttp.compiler

import com.rxhttp.compiler.ClassHelper.generatorStaticClass
import com.rxhttp.compiler.exception.ProcessingException
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType
import rxhttp.wrapper.annotation.*
import java.util.*
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic


lateinit var rxHttpPackage: String  //RxHttp相关类的包名

/**
 * User: ljx
 * Date: 2019/3/21
 * Time: 20:36
 */
//@SupportedOptions(value = ["rxhttp_rxjava", "rxhttp_package", "rxhttp_incremental", "rxhttp_debug"])
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.DYNAMIC)
open class AnnotationProcessor : AbstractProcessor() {
    companion object {
        const val rxhttp_rxjava = "rxhttp_rxjava"
        const val rxhttp_package = "rxhttp_package"
        const val rxhttp_incremental = "rxhttp_incremental"
        const val rxhttp_debug = "rxhttp_debug"
    }

    private lateinit var typeUtils: Types
    private lateinit var messager: Messager
    private lateinit var filer: Filer
    private lateinit var elementUtils: Elements
    private var incremental = true
    private var processed = false
    private var debug = false

    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        typeUtils = processingEnvironment.typeUtils
        messager = processingEnvironment.messager
        filer = processingEnvironment.filer
        elementUtils = processingEnvironment.elementUtils
        val map = processingEnvironment.options
        rxHttpPackage = map[rxhttp_package] ?: "rxhttp.wrapper.param"
        incremental = "false" != map[rxhttp_incremental]
        debug = "true" == map[rxhttp_debug]
        initRxJavaVersion(getRxJavaVersion(map))
    }

    override fun getSupportedAnnotationTypes(): Set<String> {
        return LinkedHashSet<String>().apply {
            add(Override::class.java.canonicalName)
            add(Param::class.java.canonicalName)
            add(Parser::class.java.canonicalName)
            add(Converter::class.java.canonicalName)
            add(Domain::class.java.canonicalName)
            add(DefaultDomain::class.java.canonicalName)
            add(OkClient::class.java.canonicalName)
        }
    }

    override fun getSupportedOptions(): MutableSet<String> {
        return mutableSetOf(
            rxhttp_rxjava,
            rxhttp_package,
            rxhttp_incremental,
            rxhttp_debug
        ).apply {
            if (incremental) {
                add("org.gradle.annotation.processing.aggregating")
            }
        }
    }

    open fun getRxJavaVersion(map: Map<String, String>) = map[rxhttp_rxjava]

    open fun isAndroidPlatform() = true

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (debug) {
            messager.printMessage(
                Diagnostic.Kind.WARNING,
                """
                    process isOver = ${roundEnv.processingOver()}                     
                    annotations = $annotations                     
                    rootElements = ${roundEnv.rootElements}
                """.trimIndent()
            )
        }
        if (annotations.isEmpty()) return true
        if (annotations.size == 1
            && annotations.first().toString() == "java.lang.Override"
        ) {
            val exist = roundEnv.rootElements.find {
                it.toString() == "$rxHttpPackage.RxHttp"
            }
            if (exist != null)
                return true
        }
        generatorStaticClass(filer, isAndroidPlatform())
        try {
            val rxHttpGenerator = RxHttpGenerator()
            val rxHttpWrapper = RxHttpWrapper()
            val paramsAnnotatedClass = ParamsAnnotatedClass()
            roundEnv.getElementsAnnotatedWith(Param::class.java).forEach {
                val typeElement = it as TypeElement
                checkParamsValidClass(typeElement)
                rxHttpWrapper.add(typeElement)
                paramsAnnotatedClass.add(typeElement)
            }

            val parserAnnotatedClass = ParserAnnotatedClass()
            roundEnv.getElementsAnnotatedWith(Parser::class.java).forEach {
                val typeElement = it as TypeElement
                checkParserValidClass(typeElement)
                parserAnnotatedClass.add(typeElement)
            }

            val converterAnnotatedClass = ConverterAnnotatedClass()
            roundEnv.getElementsAnnotatedWith(Converter::class.java).forEach {
                val variableElement = it as VariableElement
                checkConverterValidClass(variableElement)
                converterAnnotatedClass.add(variableElement)
                rxHttpWrapper.addConverter(variableElement)
            }

            val okClientAnnotatedClass = OkClientAnnotatedClass()
            roundEnv.getElementsAnnotatedWith(OkClient::class.java).forEach {
                val variableElement = it as VariableElement
                checkOkClientValidClass(variableElement)
                okClientAnnotatedClass.add(variableElement)
                rxHttpWrapper.addOkClient(variableElement)
            }

            val domainAnnotatedClass = DomainAnnotatedClass()
            roundEnv.getElementsAnnotatedWith(Domain::class.java).forEach {
                val variableElement = it as VariableElement
                checkVariableValidClass(variableElement)
                domainAnnotatedClass.add(variableElement)
                rxHttpWrapper.addDomain(variableElement)
            }
            rxHttpWrapper.generateRxWrapper(filer)
            val elementSet = roundEnv.getElementsAnnotatedWith(DefaultDomain::class.java)
            if (elementSet.size > 1)
                throw ProcessingException(elementSet.iterator().next(), "@DefaultDomain annotations can only be used once")
            else if (elementSet.iterator().hasNext()) {
                val variableElement = elementSet.iterator().next() as VariableElement
                checkVariableValidClass(variableElement)
                rxHttpGenerator.setAnnotatedClass(variableElement)
            }
            rxHttpGenerator.setAnnotatedClass(paramsAnnotatedClass)
            rxHttpGenerator.setAnnotatedClass(parserAnnotatedClass)
            rxHttpGenerator.setAnnotatedClass(converterAnnotatedClass)
            rxHttpGenerator.setAnnotatedClass(domainAnnotatedClass)
            rxHttpGenerator.setAnnotatedClass(okClientAnnotatedClass)

            // Generate code
            rxHttpGenerator.generateCode(filer)
            processed = true
        } catch (e: ProcessingException) {
            error(e.element, e.message)
        } catch (e: Throwable) {
            e.printStackTrace()
            messager.printMessage(Diagnostic.Kind.ERROR, e.message)
        }
        return true
    }

    @Throws(ProcessingException::class)
    private fun checkParamsValidClass(element: TypeElement) {
        if (!element.modifiers.contains(Modifier.PUBLIC)) {
            throw ProcessingException(element,
                "The class %s is not public",
                Param::class.java.simpleName)
        }
        if (element.modifiers.contains(Modifier.ABSTRACT)) {
            throw ProcessingException(element,
                "The class %s is abstract. You can't annotate abstract classes with @%",
                element.simpleName.toString(), Param::class.java.simpleName)
        }
        var currentClass = element
        while (true) {
            val interfaces = currentClass.interfaces
            for (typeMirror in interfaces) {
                if (typeMirror.toString() != "rxhttp.wrapper.param.Param<P>") continue
                return
            }
            val superClassType = currentClass.superclass
            if (superClassType.kind == TypeKind.NONE) {
                throw ProcessingException(element,
                    "The class %s annotated with @%s must inherit from %s",
                    element.qualifiedName.toString(), Param::class.java.simpleName,
                    "rxhttp.wrapper.param.Param")
            }
            currentClass = typeUtils.asElement(superClassType) as TypeElement
        }
    }

    @Throws(ProcessingException::class)
    private fun checkParserValidClass(element: TypeElement) {
        if (!element.modifiers.contains(Modifier.PUBLIC)) {
            throw ProcessingException(element,
                "The class %s is not public",
                Parser::class.java.simpleName)
        }
        if (element.modifiers.contains(Modifier.ABSTRACT)) {
            throw ProcessingException(element,
                "The class %s is abstract. You can't annotate abstract classes with @%",
                element.simpleName.toString(), Parser::class.java.simpleName)
        }

        val constructorFun = getConstructorFun(element)
        if (element.typeParameters.size > 0) {
            //有泛型的解析器不能声明为final类型
            if (element.modifiers.contains(Modifier.FINAL)) {
                throw ProcessingException(element,
                    "This class %s cannot be declared final",
                    element.simpleName.toString())
            }
            //1、查找无参构造方法
            val noArgumentConstructorFun = constructorFun.findNoArgumentConstructorFun()
                ?: throw ProcessingException(element,
                    "This class must be declared 'protected %s()' constructor method",
                    element.simpleName.toString())
            if (!noArgumentConstructorFun.modifiers.contains(Modifier.PROTECTED)) {
                //无参构造方法必须要声明为protected
                throw ProcessingException(element,
                    "This class %s no-argument constructor must be declared protected",
                    element.simpleName.toString())
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
                    throw ProcessingException(element,
                        "This class %s must declare '$method' constructor method",
                        element.simpleName.toString(), element.simpleName.toString())
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
                throw ProcessingException(element,
                    "The class %s annotated with @%s must inherit from %s",
                    element.qualifiedName.toString(), Parser::class.java.simpleName,
                    "rxhttp.wrapper.parse.Parser<T>")
            }
            //TypeMirror转TypeElement
            currentClass = typeUtils.asElement(superClassType) as TypeElement
        }
    }

    @Throws(ProcessingException::class)
    private fun checkConverterValidClass(element: VariableElement) {
        if (!element.modifiers.contains(Modifier.PUBLIC)) {
            throw ProcessingException(element,
                "The variable %s is not public",
                element.simpleName)
        }
        if (!element.modifiers.contains(Modifier.STATIC)) {
            throw ProcessingException(element,
                "The variable %s is not static",
                element.simpleName.toString())
        }
        var classType = element.asType()
        if ("rxhttp.wrapper.callback.IConverter" != classType.toString()) {
            while (true) {
                //TypeMirror转TypeElement
                val currentClass = typeUtils.asElement(classType) as TypeElement
                //遍历实现的接口有没有IConverter接口
                for (mirror in currentClass.interfaces) {
                    if (mirror.toString() == "rxhttp.wrapper.callback.IConverter") {
                        return
                    }
                }
                //未遍历到IConverter，则找到父类继续，一直循环下去，直到最顶层的父类
                classType = currentClass.superclass
                if (classType.kind == TypeKind.NONE) {
                    throw ProcessingException(element,
                        "The variable %s is not a IConverter",
                        element.simpleName.toString())
                }
            }
        }
    }

    @Throws(ProcessingException::class)
    private fun checkOkClientValidClass(element: VariableElement) {
        if (!element.modifiers.contains(Modifier.PUBLIC)) {
            throw ProcessingException(element,
                "The variable %s is not public",
                element.simpleName)
        }
        if (!element.modifiers.contains(Modifier.STATIC)) {
            throw ProcessingException(element,
                "The variable %s is not static",
                element.simpleName.toString())
        }
        val classType = element.asType()
        if ("okhttp3.OkHttpClient" != classType.toString()) {
            throw ProcessingException(element,
                "The variable %s is not a OkHttpClient", element.simpleName.toString())
        }
    }

    @Throws(ProcessingException::class)
    private fun checkVariableValidClass(element: VariableElement) {
        if (!element.modifiers.contains(Modifier.PUBLIC)) {
            throw ProcessingException(element,
                "The variable %s is not public, please add @JvmField annotation if you use kotlin",
                element.simpleName)
        }
        if (!element.modifiers.contains(Modifier.STATIC)) {
            throw ProcessingException(element,
                "The variable %s is not static",
                element.simpleName.toString())
        }
    }

    private fun error(e: Element, msg: String?, vararg args: Any) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg ?: "", *args), e)
    }

    //获取构造方法
    private fun getConstructorFun(typeElement: TypeElement): MutableList<ExecutableElement> {
        val funList = ArrayList<ExecutableElement>()
        typeElement.enclosedElements.forEach {
            if (it is ExecutableElement
                && it.kind == ElementKind.CONSTRUCTOR
                && (it.getModifiers().contains(Modifier.PUBLIC) || it.getModifiers().contains(Modifier.PROTECTED))
            ) {
                funList.add(it)
            }
        }
        return funList
    }
}