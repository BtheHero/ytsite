import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

abstract class UrlCharsetBackportVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return UrlCharsetClassVisitor(nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className.startsWith("org.schabi.newpipe.")
    }
}

private class UrlCharsetClassVisitor(
    nextClassVisitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        return UrlCharsetMethodVisitor(mv)
    }
}

private class UrlCharsetMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        val isUrlDecoderCharset = opcode == Opcodes.INVOKESTATIC &&
            owner == "java/net/URLDecoder" &&
            name == "decode" &&
            descriptor == "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"

        val isUrlEncoderCharset = opcode == Opcodes.INVOKESTATIC &&
            owner == "java/net/URLEncoder" &&
            name == "encode" &&
            descriptor == "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"

        if (isUrlDecoderCharset || isUrlEncoderCharset) {
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/charset/Charset",
                "name",
                "()Ljava/lang/String;",
                false
            )
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                name,
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false
            )
        } else {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }
}
