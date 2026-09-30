package com.elibrary.platform.security;

import com.elibrary.shared.MemberId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the authenticated principal into a {@link MemberId} so controllers can declare it
 * as a parameter. This is the single point where an HTTP/Spring Security concern becomes a
 * domain concept: nothing behind the controller sees an {@code Authentication}.
 *
 * <p>It is also why no endpoint takes a member id in its path — the caller's identity is
 * never client-supplied, so one member's data cannot be requested by guessing a URL.
 */
@Component
class MemberIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return MemberId.class.equals(parameter.getParameterType());
    }

    @Override
    public MemberId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException(
                    "No authenticated principal; every MemberId endpoint must sit behind authentication.");
        }
        return new MemberId(authentication.getName());
    }
}
