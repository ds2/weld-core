package org.jboss.webbeans.bootstrap;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.event.Observes;
import javax.inject.BindingType;
import javax.inject.DeploymentType;
import javax.inject.Initializer;
import javax.inject.Produces;
import javax.inject.Realizes;

import org.jboss.webbeans.ManagerImpl;
import org.jboss.webbeans.bean.AbstractClassBean;
import org.jboss.webbeans.bean.EnterpriseBean;
import org.jboss.webbeans.bean.NewEnterpriseBean;
import org.jboss.webbeans.bean.NewSimpleBean;
import org.jboss.webbeans.bean.ProducerFieldBean;
import org.jboss.webbeans.bean.ProducerMethodBean;
import org.jboss.webbeans.bean.RIBean;
import org.jboss.webbeans.bean.SimpleBean;
import org.jboss.webbeans.ejb.EJBApiAbstraction;
import org.jboss.webbeans.event.ObserverFactory;
import org.jboss.webbeans.event.ObserverImpl;
import org.jboss.webbeans.introspector.AnnotatedClass;
import org.jboss.webbeans.introspector.AnnotatedField;
import org.jboss.webbeans.introspector.AnnotatedMethod;
import org.jboss.webbeans.introspector.WrappedAnnotatedField;
import org.jboss.webbeans.introspector.WrappedAnnotatedMethod;
import org.jboss.webbeans.introspector.jlr.AnnotatedClassImpl;
import org.jboss.webbeans.jsf.JSFApiAbstraction;
import org.jboss.webbeans.log.LogProvider;
import org.jboss.webbeans.log.Logging;
import org.jboss.webbeans.servlet.ServletApiAbstraction;
import org.jboss.webbeans.util.Reflections;

public class BeanDeployer
{
   
   private static final LogProvider log = Logging.getLogProvider(BeanDeployer.class);
   
   private final Set<RIBean<?>> beans;
   private final Set<ObserverImpl<?>> observers;
   private final Set<AnnotatedClass<?>> classes;
   private final ManagerImpl manager;
   
   public BeanDeployer(ManagerImpl manager)
   {
      this.manager = manager;
      this.beans = new TreeSet<RIBean<?>>(new BootstrapOrderingBeanComparator());
      this.classes = new HashSet<AnnotatedClass<?>>();
      this.observers = new HashSet<ObserverImpl<?>>();
   }
   
   public BeanDeployer addBean(RIBean<?> bean)
   {
      this.beans.add(bean);
      return this;
   }
   
   public BeanDeployer addClass(Class<?> clazz)
   {
      if (!clazz.isAnnotation() && !clazz.isEnum())
      {
         classes.add(AnnotatedClassImpl.of(clazz));
      }
      return this;
   }
   
   public BeanDeployer addClasses(Iterable<Class<?>> classes)
   {
      for (Class<?> clazz : classes)
      {
         addClass(clazz);
      }
      return this;
   }
   
   public BeanDeployer createBeans()
   {
      for (AnnotatedClass<?> clazz : classes)
      {
         if (manager.getEjbDescriptorCache().containsKey(clazz.getRawType()))
         {
            createEnterpriseBean(clazz);
         }
         else if (isTypeSimpleWebBean(clazz))
         {
            createSimpleBean(clazz);
         }
      }
      return this;
   }
   
   public BeanDeployer deploy()
   {
      for (RIBean<?> bean : beans)
      {
         bean.initialize();
         log.info("Bean: " + bean);
      }
      manager.setBeans(beans);
      for (ObserverImpl<?> observer : observers)
      {
         observer.initialize();
         log.info("Observer : " + observer);
         manager.addObserver(observer);
      }
      return this;
   }
   
   public Set<RIBean<?>> getBeans()
   {
      return beans;
   }
   
   protected void printBeans()
   {
      
   }
   
   /**
    * Creates a Web Bean from a bean abstraction and adds it to the set of
    * created beans
    * 
    * Also creates the implicit field- and method-level beans, if present
    * 
    * @param bean
    *           The bean representation
    */
   protected void createBean(AbstractClassBean<?> bean, final AnnotatedClass<?> annotatedClass)
   {
      
      beans.add(bean);
      
      manager.getResolver().addInjectionPoints(bean.getInjectionPoints());
      
      createProducerMethods(bean, annotatedClass);
      createProducerFields(bean, annotatedClass);
      createObserverMethods(bean, annotatedClass);
      
      if (annotatedClass.isAnnotationPresent(Realizes.class))
      {
         createRealizedProducerMethods(bean, annotatedClass);
         createRealizedProducerFields(bean, annotatedClass);
         createRealizedObserverMethods(bean, annotatedClass);
      }
   }
   
   private void createProducerMethods(AbstractClassBean<?> declaringBean, AnnotatedClass<?> annotatedClass)
   {
      for (AnnotatedMethod<?> method : annotatedClass.getDeclaredAnnotatedMethods(Produces.class))
      {
         createProducerMethod(declaringBean, method);
         
      }
   }
   
   private void createProducerMethod(AbstractClassBean<?> declaringBean, AnnotatedMethod<?> annotatedMethod)
   {
      ProducerMethodBean<?> bean = ProducerMethodBean.of(annotatedMethod, declaringBean, manager);
      beans.add(bean);
      manager.getResolver().addInjectionPoints(bean.getInjectionPoints());
   }
   
   private void createRealizedProducerMethods(AbstractClassBean<?> declaringBean, AnnotatedClass<?> realizingClass)
   {
      AnnotatedClass<?> realizedClass = realizingClass.getSuperclass();
      for (AnnotatedMethod<?> realizedMethod : realizedClass.getDeclaredAnnotatedMethods(Produces.class))
      {
         createProducerMethod(declaringBean, realizeProducerMethod(realizedMethod, realizingClass));
      }
   }
   
   private void createRealizedProducerFields(AbstractClassBean<?> declaringBean, AnnotatedClass<?> realizingClass)
   {
      AnnotatedClass<?> realizedClass = realizingClass.getSuperclass();
      for (final AnnotatedField<?> realizedField : realizedClass.getDeclaredAnnotatedFields(Produces.class))
      {
         createProducerField(declaringBean, realizeProducerField(realizedField, realizingClass));
      }
   }
   
   private void createProducerField(AbstractClassBean<?> declaringBean, AnnotatedField<?> field)
   {
      ProducerFieldBean<?> bean = ProducerFieldBean.of(field, declaringBean, manager);
      beans.add(bean);
   }
   
   private void createProducerFields(AbstractClassBean<?> declaringBean, AnnotatedClass<?> annotatedClass)
   {
      for (AnnotatedField<?> field : annotatedClass.getDeclaredAnnotatedFields(Produces.class))
      {
         createProducerField(declaringBean, field);
      }
   }
   
   private void createObserverMethods(AbstractClassBean<?> declaringBean, AnnotatedClass<?> annotatedClass)
   {
      for (AnnotatedMethod<?> method : annotatedClass.getDeclaredMethodsWithAnnotatedParameters(Observes.class))
      {
         createObserverMethod(declaringBean, method);
      }
   }
   
   private void createRealizedObserverMethods(AbstractClassBean<?> declaringBean, AnnotatedClass<?> realizingClass)
   {
      createObserverMethods(declaringBean, realizingClass.getSuperclass());
   }
   
   private void createObserverMethod(AbstractClassBean<?> declaringBean, AnnotatedMethod<?> method)
   {
      ObserverImpl<?> observer = ObserverFactory.create(method, declaringBean, manager);
      observers.add(observer);
   }
   
   private void createSimpleBean(AnnotatedClass<?> annotatedClass)
   {
      SimpleBean<?> bean = SimpleBean.of(annotatedClass, manager);
      createBean(bean, annotatedClass);
      beans.add(NewSimpleBean.of(annotatedClass, manager));
   }
   
   private void createEnterpriseBean(AnnotatedClass<?> annotatedClass)
   {
      // TODO Don't create enterprise bean if it has no local interfaces!
      EnterpriseBean<?> bean = EnterpriseBean.of(annotatedClass, manager);
      createBean(bean, annotatedClass);
      beans.add(NewEnterpriseBean.of(annotatedClass, manager));
   }
   
   /**
    * Indicates if the type is a simple Web Bean
    * 
    * @param type
    *           The type to inspect
    * @return True if simple Web Bean, false otherwise
    */
   private boolean isTypeSimpleWebBean(AnnotatedClass<?> clazz)
   {
      Class<?> rawType = clazz.getRawType();
      EJBApiAbstraction ejbApiAbstraction = manager.getServices().get(EJBApiAbstraction.class);
      JSFApiAbstraction jsfApiAbstraction = manager.getServices().get(JSFApiAbstraction.class);
      ServletApiAbstraction servletApiAbstraction = manager.getServices().get(ServletApiAbstraction.class);
      // TODO: check 3.2.1 for more rules!!!!!!
      return !Reflections.isAbstract(rawType) && !Reflections.isParameterizedType(rawType) && !servletApiAbstraction.SERVLET_CLASS.isAssignableFrom(rawType) && !servletApiAbstraction.FILTER_CLASS.isAssignableFrom(rawType) && !servletApiAbstraction.SERVLET_CONTEXT_LISTENER_CLASS.isAssignableFrom(rawType) && !servletApiAbstraction.HTTP_SESSION_LISTENER_CLASS.isAssignableFrom(rawType) && !servletApiAbstraction.SERVLET_REQUEST_LISTENER_CLASS.isAssignableFrom(rawType) && !ejbApiAbstraction.ENTERPRISE_BEAN_CLASS.isAssignableFrom(rawType) && !jsfApiAbstraction.UICOMPONENT_CLASS.isAssignableFrom(rawType) && hasSimpleWebBeanConstructor(clazz);
   }
   
   private static boolean hasSimpleWebBeanConstructor(AnnotatedClass<?> type)
   {
      return type.getNoArgsConstructor() != null || type.getAnnotatedConstructors(Initializer.class).size() > 0;
   }
   
   private static <T> AnnotatedMethod<T> realizeProducerMethod(final AnnotatedMethod<T> method, final AnnotatedClass<?> realizingClass)
   {
      return new WrappedAnnotatedMethod<T>(method, realizingClass.getMetaAnnotations(BindingType.class))
      {
         
         @Override
         public Set<Annotation> getMetaAnnotations(Class<? extends Annotation> metaAnnotationType)
         {
            if (metaAnnotationType.equals(DeploymentType.class))
            {
               return realizingClass.getMetaAnnotations(DeploymentType.class);
            }
            else
            {
               return super.getMetaAnnotations(metaAnnotationType);
            }
         }
         
         @Override
         public Set<Annotation> getDeclaredMetaAnnotations(Class<? extends Annotation> metaAnnotationType)
         {
            if (metaAnnotationType.equals(DeploymentType.class))
            {
               return realizingClass.getDeclaredMetaAnnotations(DeploymentType.class);
            }
            else
            {
               return super.getDeclaredMetaAnnotations(metaAnnotationType);
            }
         }
         
      };
   }
   
   private static <T> AnnotatedField<T> realizeProducerField(final AnnotatedField<T> field, final AnnotatedClass<?> realizingClass)
   {
      return new WrappedAnnotatedField<T>(field, realizingClass.getMetaAnnotations(BindingType.class))
      {
         
         @Override
         public Set<Annotation> getMetaAnnotations(Class<? extends Annotation> metaAnnotationType)
         {
            if (metaAnnotationType.equals(DeploymentType.class))
            {
               return realizingClass.getMetaAnnotations(DeploymentType.class);
            }
            else
            {
               return super.getMetaAnnotations(metaAnnotationType);
            }
         }
         
         @Override
         public Set<Annotation> getDeclaredMetaAnnotations(Class<? extends Annotation> metaAnnotationType)
         {
            if (metaAnnotationType.equals(DeploymentType.class))
            {
               return realizingClass.getDeclaredMetaAnnotations(DeploymentType.class);
            }
            else
            {
               return super.getDeclaredMetaAnnotations(metaAnnotationType);
            }
         }
         
      };
   }
   
}
