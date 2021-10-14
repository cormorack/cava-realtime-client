package com.bakdata.streams_store;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.Response;

public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static boolean isPreflightRequest(ContainerRequestContext request) {
        return request.getHeaderString("Origin") != null && request.getMethod().equalsIgnoreCase("OPTIONS");
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {

        // If it's a preflight request, we abort the request
        if (isPreflightRequest(request)) {
            request.abortWith(Response.ok().build());
            return;
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {

        // if there is no Origin header, we don't do anything.
        if (request.getHeaderString("Origin") == null) {
            return;
        }

        // If it is a preflight request, then we add all
        // the CORS headers here.
        if (isPreflightRequest(request)) {
            response.getHeaders().add("Access-Control-Allow-Credentials", "true");
            response.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
            response.getHeaders().add("Access-Control-Allow-Headers",
                    // Whatever other non-standard/safe headers (see list above)
                    // you want the client to be able to send to the server,
                    // put it in this list. And remove the ones you don't want.
                    "X-Requested-With,Content-Type,Content-Length,Authorization,"
                            + "Accept,Origin,Cache-Control,Accept-Encoding,Access-Control-Request-Headers,"
                            + "Access-Control-Request-Method,Referer,x-csrftoken,ClientKey");
        }

        response.getHeaders().add("Access-Control-Allow-Origin", "*");
    }
}
