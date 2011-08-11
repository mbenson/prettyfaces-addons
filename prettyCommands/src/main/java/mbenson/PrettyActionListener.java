/*
 * Copyright 2011 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mbenson;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.el.MethodExpression;
import javax.faces.application.Application;
import javax.faces.component.UICommand;
import javax.faces.component.UIComponent;
import javax.faces.component.UINamingContainer;
import javax.faces.component.UIParameter;
import javax.faces.component.visit.VisitCallback;
import javax.faces.component.visit.VisitContext;
import javax.faces.component.visit.VisitResult;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.ActionEvent;
import javax.faces.event.ActionListener;
import javax.faces.event.ListenerFor;
import javax.faces.event.PostConstructApplicationEvent;
import javax.faces.event.SystemEvent;
import javax.faces.event.SystemEventListener;
import javax.servlet.http.HttpServletRequest;

import com.ocpsoft.pretty.PrettyContext;
import com.ocpsoft.pretty.faces.config.mapping.PathParameter;
import com.ocpsoft.pretty.faces.config.mapping.QueryParameter;
import com.ocpsoft.pretty.faces.config.mapping.RequestParameter;
import com.ocpsoft.pretty.faces.config.mapping.UrlMapping;
import com.ocpsoft.pretty.faces.servlet.PrettyFacesWrappedRequest;
import com.ocpsoft.pretty.faces.util.FacesElUtils;
import com.ocpsoft.pretty.faces.util.NullComponent;

/**
 * Handles JSF UICommand actions that point to pretty mappings. Specifically
 * does this by applying {@code f:param} components found on a UICommand to the
 * current request respective to these parameters' representation on any
 * {@code UrlMapping} matching the command's {@code action}. The strategy here
 * is that named {@code f:param}s are mapped to matching path or query
 * parameters, while nameless {@code f:param}s are mapped, in the order
 * encountered, to correponding path parameters, skipping any path parameters
 * specified by name.
 * 
 * @author Matt Benson
 * @author Lincoln Baxter, III <lincoln@ocpsoft.com>
 */
public class PrettyActionListener implements ActionListener {
    /**
     * Installs the pretty ActionListener
     */
    @ListenerFor(systemEventClass = PostConstructApplicationEvent.class)
    public static class Install implements SystemEventListener {

        public boolean isListenerForSource(Object source) {
            return source instanceof Application;
        }

        public void processEvent(SystemEvent event) throws AbortProcessingException {
            final Application app = (Application) event.getSource();
            app.setActionListener(new PrettyActionListener(app.getActionListener()));
        }

    }

    private static final FacesElUtils elUtils = new FacesElUtils();

    private final ActionListener delegate;

    /**
     * Create a new listener instance.
     * 
     * @param delegate
     *            original ActionListener
     */
    private PrettyActionListener(ActionListener delegate) {
        super();
        this.delegate = delegate;
    }

    /**
     * Process ActionEvents.
     * 
     * @param event
     *            to process
     */
    public void processAction(ActionEvent event) throws AbortProcessingException {
        try {
            if (event.getComponent() instanceof UICommand) {
                PrettyContext pretty = PrettyContext.getCurrentInstance();
                UICommand cmd = (UICommand) event.getComponent();
                MethodExpression action = cmd.getActionExpression();
                if (action != null) {
                    final UrlMapping mapping = pretty.getConfig().getMappingById(action.getExpressionString());
                    if (mapping != null) {
                        injectParams(FacesContext.getCurrentInstance(), cmd, mapping);
                    }
                }
            }
        } finally {
            delegate.processAction(event);
        }
    }

    /**
     * Adapted from com.ocpsoft.pretty.faces.beans.ParameterInjector code.
     * 
     * @param context
     * @param cmd
     * @param mapping
     */
    private void injectParams(final FacesContext context, final UICommand cmd, final UrlMapping mapping) {
        final List<PathParameter> pathParams = mapping.getPatternParser().getPathParameters();
        final List<QueryParameter> queryParams = mapping.getQueryParams();

        if (pathParams.isEmpty() && queryParams.isEmpty()) {
            return;
        }

        final HashMap<String, RequestParameter> namedParams =
            new HashMap<String, RequestParameter>(queryParams.size() + pathParams.size());

        for (QueryParameter p : queryParams) {
            namedParams.put(p.getName(), p);
        }
        for (PathParameter p : pathParams) {
            if (p.isNamed()) {
                namedParams.put(p.getName(), p);
            }
        }
        final HashMap<Object, Object> handledNames = new HashMap<Object, Object>();

        // do a full visit to ensure all potentially necessary context is
        // available:
        VisitContext visitContext =
            VisitContext.createVisitContext(
                context,
                Collections.singleton(String.format("%s%s", UINamingContainer.getSeparatorChar(context),
                    cmd.getClientId(context))), null);
        context.getViewRoot().visitTree(visitContext, new VisitCallback() {

            public VisitResult visit(VisitContext visitContext, UIComponent target) {
                if (target == cmd) {
                    // nested visit for named params
                    target.visitTree(VisitContext.createVisitContext(context), new VisitCallback() {

                        public VisitResult visit(VisitContext visitContext, UIComponent target) {
                            if (target instanceof UIParameter) {
                                UIParameter param = (UIParameter) target;
                                Object name = param.getAttributes().get("name");
                                if (name != null && namedParams.containsKey(name)) {
                                    Object value = param.getAttributes().get("value");
                                    setValue(context, namedParams.get(name), value);
                                    handledNames.put(name, value);
                                }
                                return VisitResult.REJECT;
                            }
                            return VisitResult.ACCEPT;
                        }
                    });
                    // nested visit for remaining unnamed, thus presumed to be
                    // path params
                    final Iterator<PathParameter> orderedPathParams = pathParams.iterator();
                    target.visitTree(VisitContext.createVisitContext(context), new VisitCallback() {

                        public VisitResult visit(VisitContext visitContext, UIComponent target) {
                            if (target instanceof UIParameter) {
                                UIParameter param = (UIParameter) target;
                                if (param.getAttributes().get("name") == null) {
                                    if (orderedPathParams.hasNext()) {
                                        PathParameter pathParam = orderedPathParams.next();
                                        // skip path params already handled by
                                        // name:
                                        while (handledNames.containsKey(pathParam.getName())) {
                                            if (orderedPathParams.hasNext()) {
                                                pathParam = orderedPathParams.next();
                                            } else {
                                                pathParam = null;
                                                break;
                                            }
                                        }
                                        if (pathParam != null) {
                                            setValue(context, pathParam, param.getAttributes().get("value"));
                                        }
                                    } else {
                                        return VisitResult.COMPLETE;
                                    }
                                }
                                return VisitResult.REJECT;
                            }
                            return VisitResult.ACCEPT;
                        }
                    });
                    return VisitResult.COMPLETE;
                }
                return target instanceof UICommand ? VisitResult.REJECT : VisitResult.ACCEPT;
            }
        });
        HashMap<String, String[]> additionalRequestParameters = new HashMap<String, String[]>();
        for (Map.Entry<Object, Object> e : handledNames.entrySet()) {
            if (e.getKey() instanceof String) {
                if (e.getValue() instanceof String) {
                    additionalRequestParameters.put((String) e.getKey(), new String[] { (String) e.getValue() });
                } else if (e.getValue() instanceof String[]) {
                    additionalRequestParameters.put((String) e.getKey(), (String[]) e.getValue());
                }
            }
        }
        if (!additionalRequestParameters.isEmpty()) {
            context.getExternalContext().setRequest(
                new PrettyFacesWrappedRequest((HttpServletRequest) context.getExternalContext().getRequest(),
                    additionalRequestParameters));
        }
    }

    private static void setValue(final FacesContext context, RequestParameter param, Object value) {
        value = overrideLiteralPathParameter(param, value);

        String el = param.getExpression().getELExpression();
        if (el == null || "".equals(el.trim())) {
            return;
        }
        Object convertedValue = null;
        Class<?> expectedType = elUtils.getExpectedType(context, el);
        if (expectedType != null && !expectedType.isInstance(value) && value instanceof String) {
            // get the type of the referenced property and try to obtain a
            // converter
            // for it
            Converter converter = context.getApplication().createConverter(expectedType);

            // Use the convert to create the correct type
            if (converter != null) {
                convertedValue = converter.getAsObject(context, new NullComponent(), (String) value);
            }
        }
        elUtils.setValue(context, el, convertedValue == null ? value : convertedValue);
    }

    private static Object overrideLiteralPathParameter(RequestParameter param, Object value) {
        if (param instanceof PathParameter) {
            String regex = ((PathParameter) param).getRegex();
            if (regex != null && regex.equals(Pattern.quote(regex))) {
                return regex;
            }
        }
        return value;
    }
}
