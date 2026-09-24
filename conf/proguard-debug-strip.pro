# Debug-info stripping for the release bundle's precompiled app classes.
#
# This does NOT rename, shrink, or optimise anything — every class, member and
# name is kept exactly. The only effect is dropping the code attributes that are
# not listed under -keepattributes below, which is where a decompiler reads the
# original local-variable and parameter names from. Renaming was spiked
# (project_obfuscation_spike) and deferred: it breaks Play's string-based class
# resolution and needs a commercial tool to make logic unreadable, whereas
# stripping is behaviour-neutral. -injars / -outjars / -libraryjars are supplied
# by the `stripPrecompiledDebugInfo` Gradle task.
-dontshrink
-dontoptimize
-keep class ** { *; }

# What is kept, and why each line is load-bearing:
#  - MethodParameters: Play binds controller action arguments by parameter name
#    (play.utils.Java.parameterNames -> Parameter.getName()). Strip this and every
#    controller with named params breaks at request time, not at boot.
#  - LineNumberTable + SourceFile: production stack traces keep file:line. Dropping
#    them takes the size reduction from ~10% to ~16% but leaves every trace at
#    "Unknown Source" — not worth it for a running product.
#  - Signature / annotations / Inner-Enclosing-Nest / Record / PermittedSubclasses
#    / Exceptions: read reflectively at runtime by Play, Hibernate, Gson and the
#    OpenAPI generator, and needed for records and sealed types to resolve.
# LocalVariableTable and LocalVariableTypeTable are deliberately ABSENT: they carry
# the local and parameter *names* a decompiler reprints, and removing them is the
# entire point of this step.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Record,PermittedSubclasses,NestHost,NestMembers,Exceptions,MethodParameters,LineNumberTable,SourceFile

-dontwarn **
-dontnote **
