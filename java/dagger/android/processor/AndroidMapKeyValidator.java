/*
 * Copyright (C) 2017 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.android.processor;

import static com.google.auto.common.AnnotationMirrors.getAnnotatedAnnotations;
import static com.google.auto.common.AnnotationMirrors.getAnnotationValue;
import static com.google.auto.common.MoreElements.getAnnotationMirror;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import dagger.Binds;
import dagger.android.ActivityKey;
import dagger.android.AndroidInjector;
import dagger.android.FragmentKey;
import java.lang.annotation.Annotation;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.inject.Qualifier;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Validates the correctness of {@link ActivityKey}, {@link FragmentKey} and {@link
 * dagger.android.support.FragmentKey} methods.
 */
final class AndroidMapKeyValidator implements ProcessingStep {

  private static final ImmutableBiMap<Class<? extends Annotation>, String>
      ANNOTATIONS_AND_FRAMEWORK_TYPES =
          ImmutableBiMap.of(
              ActivityKey.class, "android.app.Activity",
              FragmentKey.class, "android.app.Fragment",
              dagger.android.support.FragmentKey.class, "android.support.v4.app.Fragment");
  private static final String LINK_TO_DOCS = "google.github.io/dagger/android";

  private final Elements elements;
  private final Types types;
  private final Messager messager;

  AndroidMapKeyValidator(Elements elements, Types types, Messager messager) {
    this.elements = elements;
    this.types = types;
    this.messager = messager;
  }

  @Override
  public Set<? extends Class<? extends Annotation>> annotations() {
    return ANNOTATIONS_AND_FRAMEWORK_TYPES.keySet();
  }

  @Override
  public Set<Element> process(
      SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
    elementsByAnnotation.forEach(
        (annotation, element) -> validateMethod(annotation, MoreElements.asExecutable(element)));
    return ImmutableSet.of();
  }

  private void validateMethod(Class<? extends Annotation> annotation, ExecutableElement method) {
    if (!getAnnotatedAnnotations(method, Qualifier.class).isEmpty()) {
      return;
    }

    TypeMirror returnType = method.getReturnType();
    if (!types.isAssignable(types.erasure(returnType), factoryElement().asType())) {
      // if returnType is not related to AndroidInjector.Factory, ignore the method
      return;
    }

    DeclaredType intendedReturnType =
        injectorFactoryOf(types.getWildcardType(androidTypeForMapKey(annotation), null));
    if (!MoreTypes.equivalence().equivalent(returnType, intendedReturnType)) {
      messager.printMessage(
          Kind.ERROR,
          String.format(
              "@%s methods should bind %s, not %s. See %s",
              annotation.getCanonicalName(), intendedReturnType, returnType, LINK_TO_DOCS),
          method);
    }

    // @Binds methods should only have one parameter, but we can't guarantee the order of Processors
    // in javac, so do a basic check for valid form
    if (isAnnotationPresent(method, Binds.class) && method.getParameters().size() == 1) {
      validateMapKeyMatchesBindsParameter(annotation, method);
    }
  }

  /**
   * A valid @Binds method could bind an {@link AndroidInjector.Factory} for one type, while giving
   * it a map key of a different type. The return type and parameter type would pass typical @Binds
   * validation, but the map lookup in {@link dagger.android.DispatchingAndroidInjector} would
   * retrieve the wrong injector factory.
   *
   * <pre>{@code
   * {@literal @Binds}
   * {@literal @IntoMap}
   * {@literal @ActivityKey(GreenActivity.class)}
   * abstract AndroidInjector.Factory<? extends Activity> bindBlueActivity(
   *     BlueActivityComponent.Builder builder);
   * }</pre>
   */
  private void validateMapKeyMatchesBindsParameter(
      Class<? extends Annotation> annotation, ExecutableElement method) {
    TypeMirror parameterType = getOnlyElement(method.getParameters()).asType();
    AnnotationMirror annotationMirror = getAnnotationMirror(method, annotation).get();
    TypeMirror mapKeyValue = (TypeMirror) getAnnotationValue(annotationMirror, "value").getValue();
    if (!types.isAssignable(parameterType, injectorFactoryOf(mapKeyValue))) {
      messager.printMessage(
          Kind.ERROR,
          String.format("%s does not implement AndroidInjector<%s>", parameterType, mapKeyValue),
          method,
          annotationMirror);
    }
  }

  private DeclaredType androidTypeForMapKey(Class<? extends Annotation> annotation) {
    return types.getDeclaredType(
        elements.getTypeElement(ANNOTATIONS_AND_FRAMEWORK_TYPES.get(annotation)));
  }

  /** Returns a {@link DeclaredType} for {@code AndroidInjector.Factory<implementationType>}. */
  private DeclaredType injectorFactoryOf(TypeMirror implementationType) {
    return types.getDeclaredType(factoryElement(), implementationType);
  }

  private TypeElement factoryElement() {
    return elements.getTypeElement(AndroidInjector.Factory.class.getCanonicalName());
  }
}
