package me.modmuss50.optifabric.compat;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;

import org.apache.commons.lang3.StringUtils;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;
import org.spongepowered.asm.util.Annotations;

import me.modmuss50.optifabric.util.MixinFinder;
import me.modmuss50.optifabric.util.MixinFinder.Mixin;
import me.modmuss50.optifabric.util.RemappingUtils;

public class InterceptingMixinPlugin extends EmptyMixinPlugin {
	/*@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		try {
			ClassNode mixin = MixinService.getService().getBytecodeProvider().getClassNode(mixinClassName, false);
			return Annotations.getInvisible(mixin, DevOnly.class) == null || FabricLoader.getInstance().isDevelopmentEnvironment();
		} catch (ClassNotFoundException | IOException e) {
			System.err.println("Error fetching " + mixinClassName + " transforming " + targetClassName);
			e.printStackTrace();
			return true; //Possibly should be returning false if it can't be found?
		}
	}*/

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		ClassNode thisMixin = Mixin.create(mixinInfo).getClassNode();

		AnnotationNode interception = Annotations.getInvisible(thisMixin, InterceptingMixin.class);
		if (interception == null) return; //Nothing to do for this particular Mixin

		Mixin interceptionMixin = findMixin(targetClassName, Annotations.getValue(interception));
		on: for (MethodNode method : thisMixin.methods) {
			AnnotationNode surrogateNode = Annotations.getInvisible(method, PlacatingSurrogate.class);

			if (surrogateNode != null) {
				for (Method realMethod : interceptionMixin.getMethods()) {
					if (realMethod.getOriginalName().equals(method.name)) {
						method.name = realMethod.getName(); //Mangle name to whatever Mixin is using for the real injection
						method.invisibleAnnotations.remove(surrogateNode);
						Annotations.setVisible(method, Surrogate.class);

						String coercedDesc = coerceDesc(method);
						if (coercedDesc != null) method.desc = coercedDesc;

						targetClass.methods.add(method);
						continue on;
					}
				}

				throw new IllegalStateException("Cannot find original Mixin method for surrogate " + method.name + method.desc + " in " + interceptionMixin);	
			}
		}
	}

	private static Mixin findMixin(String targetClass, String mixinTarget) {
		for (Mixin mixin : MixinFinder.getMixinsFor(targetClass)) {
			if (mixinTarget.equals(mixin.getName())) {
				return mixin;
			}
		}

		throw new IllegalArgumentException("Can't find Mixin class " + mixinTarget + " targetting " + targetClass);
	}

	private static String coerceDesc(MethodNode method) {
		if (method.invisibleParameterAnnotations != null) {
			Type[] arguments = Type.getArgumentTypes(method.desc);
			boolean madeChange = false;

			for (int i = 0, end = arguments.length; i < end; i++) {
				AnnotationNode coercionNode = Annotations.getInvisibleParameter(method, LoudCoerce.class, i);

				if (coercionNode != null) {
					String type = Annotations.getValue(coercionNode);

					if (Annotations.<Boolean>getValue(coercionNode, "remap") != Boolean.FALSE) {
						type = RemappingUtils.getClassName(type);
					}

					arguments[i] = Type.getObjectType(type);
					madeChange = true;
				}
			}

			if (madeChange) return Type.getMethodDescriptor(Type.getReturnType(method.desc), arguments);
		}

		return null;
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		ClassNode thisMixin = Mixin.create(mixinInfo).getClassNode();

		AnnotationNode interception = Annotations.getInvisible(thisMixin, InterceptingMixin.class);
		if (interception == null) return; //Nothing to do for this particular Mixin

		Mixin interceptionMixin = findMixin(targetClassName, Annotations.getValue(interception));
		Map<String, Method> shims = thisMixin.methods.stream().filter(method -> Annotations.getInvisible(method, Shim.class) != null).collect(Collectors.toMap(method -> method.name.concat(method.desc), method -> {
			Method realMethod = interceptionMixin.getMethod(method.name, MoreObjects.firstNonNull(coerceDesc(method), method.desc));

			if (realMethod == null) {
				throw new IllegalStateException("Cannot find shim method " + method.name + method.desc + " in " + interceptionMixin);
			}

			assert method.name.equals(realMethod.getOriginalName());
			return realMethod;
		}));
		if (shims.isEmpty()) return; //Nothing to do

		targetClassName = targetClassName.replace('.', '/');
		for (Iterator<MethodNode> it = targetClass.methods.iterator(); it.hasNext();) {
			MethodNode method = it.next();

			if (shims.containsKey(method.name.concat(method.desc)) || Annotations.getInvisible(method, PlacatingSurrogate.class) != null) {
				it.remove(); //Don't want to keep the shim methods
			} else {
				method.desc = StringUtils.replace(method.desc, "Lnull;", "Ljava/lang/Object;");

				for (AbstractInsnNode insn : method.instructions) {
					if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
						MethodInsnNode methodInsn = (MethodInsnNode) insn;

						Method replacedMethod = shims.get(methodInsn.name.concat(methodInsn.desc));
						if (replacedMethod != null && targetClassName.equals(methodInsn.owner)) {
							methodInsn.name = replacedMethod.getName();

							if (!methodInsn.desc.equals(replacedMethod.getDesc())) {
								Type[] existingArgs = Type.getArgumentTypes(methodInsn.desc);
								Type[] replacementArgs = Type.getArgumentTypes(replacedMethod.getDesc());

								for (int index = 0, end = existingArgs.length; index < end; index++) {
									if (!existingArgs[index].equals(replacementArgs[index])) {
										AbstractInsnNode target = insn;

										for (int i = end - 1; i > index; i--) {
											do {//If target is ever null the method underflowed instructions => would fail verification anyway
												target = Objects.requireNonNull(target.getPrevious());
											} while (target.getType() == AbstractInsnNode.LINE || target.getType() == AbstractInsnNode.LABEL);
										}

										if (target.getType() != AbstractInsnNode.VAR_INSN || target.getOpcode() != existingArgs[index].getOpcode(Opcodes.ILOAD)) {
											//Be under no illusions that passing this is necessarily safe, just it's more probably save than entering here
											throw new UnsupportedOperationException("Unexpectedly complex stack unwinding requested");
										}

										method.instructions.insertBefore(target, new TypeInsnNode(Opcodes.CHECKCAST, replacementArgs[index].getInternalName()));
									}
								}

								methodInsn.desc = replacedMethod.getDesc();
							}
						}
					}
				}
			}
		}
	}
}