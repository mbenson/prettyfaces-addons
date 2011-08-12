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
package mbenson.pretty;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.el.MethodExpression;
import javax.faces.application.Application;
import javax.faces.component.UICommand;
import javax.faces.component.UIParameter;
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
import com.ocpsoft.pretty.faces.util.PrettyURLBuilder;

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
     * Installs the pretty ActionListener.
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

    private static final FacesElUtils EL_UTILS = new FacesElUtils();
    private static final PrettyURLBuilder URL_BUILDER = new PrettyURLBuilder();

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
                        new ParameterHandler(FacesContext.getCurrentInstance(), cmd, mapping).handle();
                    }
                }
            }
        } finally {
            delegate.processAction(event);
        }
    }

    private static class ParameterHandler {
        final UICommand cmd;
        final FacesContext context;

        final List<PathParameter> pathParams;
        final List<QueryParameter> queryParams;
        final Map<String, Object> handledParams = new HashMap<String, Object>();

        ParameterHandler(FacesContext context, UICommand cmd, UrlMapping mapping) {
            super();
            this.context = context;
            this.cmd = cmd;
            pathParams = mapping.getPatternParser().getPathParameters();
            queryParams = mapping.getQueryParams();
        }

        void handle() {
            handleNamedParams();
            handlePathParams();
            addNamedRequestParameters();
        }

        private void handleNamedParams() {
            comp: for (UIParameter param : URL_BUILDER.extractParameters(cmd)) {
                String name = param.getName();
                if (isBlank(name)) {
                    continue;
                }
                RequestParameter found = null;
                for (PathParameter p : pathParams) {
                    if (name.equals(p.getName())) {
                        found = p;
                        continue comp;
                    }
                }
                if (found == null) {
                    for (QueryParameter p : queryParams) {
                        if (name.equals(p.getName())) {
                            found = p;
                        }
                    }
                }
                if (found != null) {
                    Object value = param.getAttributes().get("value");
                    if (hasLiteralRegex(found)) {
                        value = ((PathParameter) found).getRegex();
                    }
                    setValue(found, value);
                    handledParams.put(name, value);
                }
            }
        }

        private void handlePathParams() {
            final Iterator<PathParameter> orderedPathParams = pathParams.iterator();
            for (UIParameter param : URL_BUILDER.extractParameters(cmd)) {
                if (param.getName() == null) {
                    if (orderedPathParams.hasNext()) {
                        PathParameter pathParam = orderedPathParams.next();
                        // skip path params already handled by name:
                        while (handledParams.containsKey(pathParam.getName())) {
                            if (orderedPathParams.hasNext()) {
                                pathParam = orderedPathParams.next();
                            } else {
                                pathParam = null;
                                break;
                            }
                        }
                        if (pathParam != null) {
                            Object value = param.getValue();
                            if (hasLiteralRegex(pathParam)) {
                                value = pathParam.getRegex();
                            }
                            setValue(pathParam, value);
                        }
                    } else {
                        break;
                    }
                }
            }
            while (orderedPathParams.hasNext()) {
                PathParameter p = orderedPathParams.next();
                if (hasLiteralRegex(p)) {
                    setValue(p, p.getRegex());
                }
            }
        }

        /*
         * Code adapted from com.ocpsoft.pretty.faces.beans.ParameterInjector
         */
        private void setValue(RequestParameter param, Object value) {
            String el = param.getExpression().getELExpression();
            if (el == null || "".equals(el.trim())) {
                return;
            }
            Object convertedValue = null;
            Class<?> expectedType = EL_UTILS.getExpectedType(context, el);
            if (expectedType != null && !expectedType.isInstance(value) && value instanceof String) {
                // get the type of the referenced property and try to obtain a converter for it
                Converter converter = context.getApplication().createConverter(expectedType);

                // Use the convert to create the correct type
                if (converter != null) {
                    convertedValue = converter.getAsObject(context, new NullComponent(), (String) value);
                }
            }
            EL_UTILS.setValue(context, el, convertedValue == null ? value : convertedValue);
        }

        private void addNamedRequestParameters() {
            HashMap<String, String[]> additionalRequestParameters = new HashMap<String, String[]>();
            for (Map.Entry<String, Object> e : handledParams.entrySet()) {
                if (e.getValue() instanceof String) {
                    additionalRequestParameters.put(e.getKey(), new String[] { (String) e.getValue() });
                } else if (e.getValue() instanceof String[]) {
                    additionalRequestParameters.put(e.getKey(), (String[]) e.getValue());
                }
            }
            if (!additionalRequestParameters.isEmpty()) {
                context.getExternalContext().setRequest(
                    new PrettyFacesWrappedRequest((HttpServletRequest) context.getExternalContext().getRequest(),
                        additionalRequestParameters));
            }
        }

        private static boolean hasLiteralRegex(RequestParameter param) {
            if (param instanceof PathParameter) {
                String regex = ((PathParameter) param).getRegex();
                return regex != null && regex.equals(Pattern.quote(regex));
            }
            return false;
        }

        private static boolean isBlank(String s) {
            return s == null || s.trim().length() == 0;
        }
    }

}
