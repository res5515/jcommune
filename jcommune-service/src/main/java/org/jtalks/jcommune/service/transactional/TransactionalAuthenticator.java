/**
 * Copyright (C) 2011  JTalks.org Team
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.jtalks.jcommune.service.transactional;

import org.joda.time.DateTime;
import org.jtalks.common.model.entity.User;
import org.jtalks.common.service.security.SecurityContextHolderFacade;
import org.jtalks.jcommune.model.dao.UserDao;
import org.jtalks.jcommune.model.dto.RegisterUserDto;
import org.jtalks.jcommune.model.entity.JCUser;
import org.jtalks.jcommune.model.entity.Language;
import org.jtalks.jcommune.model.plugins.Plugin;
import org.jtalks.jcommune.model.plugins.SimpleAuthenticationPlugin;
import org.jtalks.jcommune.model.plugins.exceptions.NoConnectionException;
import org.jtalks.jcommune.model.plugins.exceptions.UnexpectedErrorException;
import org.jtalks.jcommune.service.Authenticator;
import org.jtalks.jcommune.service.exceptions.NotFoundException;
import org.jtalks.jcommune.service.nontransactional.EncryptionService;
import org.jtalks.jcommune.service.nontransactional.ImageService;
import org.jtalks.jcommune.service.nontransactional.MailService;
import org.jtalks.jcommune.service.plugins.PluginFilter;
import org.jtalks.jcommune.service.plugins.PluginLoader;
import org.jtalks.jcommune.service.plugins.TypeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

/**
 * Serves for authentication and registration user.
 * Authentication:
 * Firstly tries to authenticate by default behavior (with JCommune authentication).
 * If default authentication was failed tries to authenticate by any available plugin.
 * <p/>
 * Registration:
 * Register user by any available plugin.
 *
 * @author Andrey Pogorelov
 */
public class TransactionalAuthenticator extends AbstractTransactionalEntityService<JCUser, UserDao>
        implements Authenticator {

    /**
     * While registering a new user, she gets {@link JCUser#setAutosubscribe(boolean)} set to {@code true} by default.
     * Afterwards user can edit her profile and change this setting.
     */
    public static final boolean DEFAULT_AUTOSUBSCRIBE = true;

    private PluginLoader pluginLoader;
    private EncryptionService encryptionService;
    private AuthenticationManager authenticationManager;
    private SecurityContextHolderFacade securityFacade;
    private RememberMeServices rememberMeServices;
    private SessionAuthenticationStrategy sessionStrategy;
    private MailService mailService;
    private ImageService avatarService;

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalAuthenticator.class);


    /**
     * @param pluginLoader          used for obtain auth plugin
     * @param dao                   for operations with data storage
     * @param encryptionService     encodes user password
     * @param authenticationManager used in login logic
     * @param securityFacade        used in login logic
     * @param rememberMeServices    used in login logic to specify remember user or
     *                              not
     * @param sessionStrategy       used in login logic to call onAuthentication hook
     *                              which stored this user to online uses list.
     */
    public TransactionalAuthenticator(PluginLoader pluginLoader, UserDao dao,
                                      EncryptionService encryptionService,
                                      AuthenticationManager authenticationManager,
                                      SecurityContextHolderFacade securityFacade,
                                      RememberMeServices rememberMeServices,
                                      SessionAuthenticationStrategy sessionStrategy,
                                      MailService mailService,
                                      ImageService avatarService) {
        super(dao);
        this.pluginLoader = pluginLoader;
        this.encryptionService = encryptionService;
        this.authenticationManager = authenticationManager;
        this.securityFacade = securityFacade;
        this.rememberMeServices = rememberMeServices;
        this.sessionStrategy = sessionStrategy;
        this.mailService = mailService;
        this.avatarService = avatarService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean authenticate(String username, String password, boolean rememberMe,
                                HttpServletRequest request, HttpServletResponse response)
            throws UnexpectedErrorException, NoConnectionException {
        boolean result;
        JCUser user;
        try {
            user = getByUsername(username);
            result = authenticateDefault(user, password, rememberMe, request, response);
        } catch (NotFoundException | AuthenticationException e) {
            boolean newUser = false;
            if (e instanceof NotFoundException) {
                String ipAddress = getClientIpAddress(request);
                LOGGER.info("User was not found during login process, username = {}, IP={}", username, ipAddress);
                newUser = true;
            }
            result = authenticateByPluginAndUpdateUserInfo(username, password, newUser, rememberMe, request, response);
        }
        return result;
    }

    /**
     * Authenticate user by auth plugin and save updated user details to inner database.
     *
     *
     * @param username username
     * @param password user password
     * @param newUser  is new user or not
     * @return true if authentication was successful, otherwise false
     * @throws UnexpectedErrorException if some unexpected error occurred
     * @throws NoConnectionException    if some connection error occurred
     */
    private boolean authenticateByPluginAndUpdateUserInfo(String username, String password, boolean newUser,
                                                          boolean rememberMe, HttpServletRequest request,
                                                          HttpServletResponse response)
            throws UnexpectedErrorException, NoConnectionException {
        String passwordHash = encryptionService.encryptPassword(password);
        Map<String, String> authInfo = authenticateByAvailablePlugin(username, passwordHash);
        if (authInfo.isEmpty() || !authInfo.containsKey("email") || !authInfo.containsKey("username")) {
            LOGGER.info("Could not authenticate user '{}' by plugin.", username);
            return false;
        }
        JCUser user = saveUser(authInfo, passwordHash, newUser);
        try {
            return authenticateDefault(user, password, rememberMe, request, response);
        } catch (AuthenticationException e) {
            return false;
        }
    }

    /**
     * Authenticate user by JCommune.
     *
     *
     * @param user       user entity
     * @param password   user password
     * @param rememberMe remember this user or not
     * @param request    HTTP request
     * @param response   HTTP response
     * @return true if authentication was successful, otherwise false
     * @throws AuthenticationException
     */
    private boolean authenticateDefault(JCUser user, String password, boolean rememberMe,
                                        HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(user.getUsername(), password);
            token.setDetails(user);
            Authentication auth = authenticationManager.authenticate(token);
            securityFacade.getContext().setAuthentication(auth);
            if (auth.isAuthenticated()) {
                sessionStrategy.onAuthentication(auth, request, response);
                if (rememberMe) {
                    rememberMeServices.loginSuccess(request, response, auth);
                }
                user.updateLastLoginTime();
                return true;
            }
        } catch (AuthenticationException e) {
            String ipAddress = getClientIpAddress(request);
            LOGGER.info("AuthenticationException: username = {}, IP={}, message={}",
                    new Object[]{user.getUsername(), ipAddress, e.getMessage()});
            throw e;
        }
        return false;
    }

    /**
     * Process authentication with available plugin.
     *
     * @param username     username
     * @param passwordHash user password hash
     * @return user auth details returned by authentication plugin
     * @throws UnexpectedErrorException if some unexpected error occurred
     * @throws NoConnectionException    if some connection error occurred
     */
    private Map<String, String> authenticateByAvailablePlugin(String username, String passwordHash)
            throws UnexpectedErrorException, NoConnectionException {

        SimpleAuthenticationPlugin authPlugin = getPlugin();
        Map<String, String> authInfo = new HashMap<>();
        if (authPlugin != null && authPlugin.getState() == Plugin.State.ENABLED) {
            authInfo.putAll(authPlugin.authenticate(username, passwordHash));
        }
        return authInfo;
    }

    private JCUser getByUsername(String username) throws NotFoundException {
        JCUser user = this.getDao().getByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }
        return user;
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null) {
            ipAddress = request.getRemoteAddr();
        }
        return ipAddress;
    }


    /**
     * Save (or update) user with specified details in internal database.
     *
     * @param authInfo     user details
     * @param passwordHash user password hash
     * @param newUser      user is new for JCommune
     * @return saved (or updated) user
     */
    private JCUser saveUser(Map<String, String> authInfo, String passwordHash, boolean newUser) {
        JCUser user;
        if (newUser) {
            user = getDao().getByUsername(authInfo.get("username"));
            if (user != null) {
                // user already exist in database (poulpe uses the same database),
                // no need to create or update update him
                return user;
            } else {
                // user not exist in database (poulpe uses own database)
                user = new JCUser(authInfo.get("username"), authInfo.get("email"), passwordHash);
            }
        } else {
            user = getDao().getByUsername(authInfo.get("username"));
            user.setPassword(passwordHash);
            user.setEmail(authInfo.get("email"));
        }
        if (authInfo.containsKey("firstName")) {
            user.setFirstName(authInfo.get("firstName"));
        }
        if (authInfo.containsKey("lastName")) {
            user.setLastName(authInfo.get("lastName"));
        }
        getDao().saveOrUpdate(user);
        return user;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JCUser register(RegisterUserDto userDto, Locale locale, Validator validator, BindingResult bindingResult)
            throws UnexpectedErrorException, NoConnectionException {
        SimpleAuthenticationPlugin authPlugin = getPlugin();
        if (authPlugin != null && authPlugin.getState() == Plugin.State.ENABLED) {
            String passwordHash = (userDto.getPassword() == null || userDto.getPassword().isEmpty()) ? ""
                    : encryptionService.encryptPassword(userDto.getPassword());
            List<Map<String, String>> errors
                    = authPlugin.registerUser(userDto.getUsername(), passwordHash, userDto.getEmail());
            parseValidationErrors(errors, bindingResult, locale);
        } else {
            validator.validate(userDto, bindingResult);
        }
        if(!bindingResult.hasErrors()) {
            return storeUser(userDto, locale);
        }
        return null;
    }

    /**
     * Get available plugin by plugin loader.
     *
     * @return authentication plugin
     */
    protected SimpleAuthenticationPlugin getPlugin() {
        Class cl = SimpleAuthenticationPlugin.class;
        PluginFilter pluginFilter = new TypeFilter(cl);
        List<Plugin> plugins = pluginLoader.getPlugins(pluginFilter);
        return !plugins.isEmpty() ? (SimpleAuthenticationPlugin) plugins.get(0) : null;
    }

    /**
     * Just registers a new user without any additional checks, it gets rid of duplication in enclosing
     * {@code registerUser()} methods.
     *
     * @param userDto coming from enclosing methods, this object is built by Spring MVC
     * @param locale  the locale of user she can pass in GET requests
     */
    private JCUser storeUser(RegisterUserDto userDto, Locale locale) {
        JCUser user = userDto.createUser();
        user.setLanguage(Language.byLocale(locale));
        user.setAutosubscribe(DEFAULT_AUTOSUBSCRIBE);
        user.setAvatar(avatarService.getDefaultImage());

        // check if user already saved by plugin as common user
        User commonUser = this.getDao().getCommonUserByUsername(userDto.getUsername());
        if(commonUser != null) {
            // in this case just update him
            this.getDao().delete(commonUser.getId());
        }
        String encodedPassword = encryptionService.encryptPassword(user.getPassword());
        user.setPassword(encodedPassword);
        user.setRegistrationDate(new DateTime());
        this.getDao().saveOrUpdate(user);
        mailService.sendAccountActivationMail(user);
        LOGGER.info("JCUser registered: {}", user.getUsername());
        return user;
    }

    /**
     * Parse validation error codes with available {@link java.util.ResourceBundle} to {@link BindingResult}.
     *
     * @param errors errors occurred while registering user
     * @param result result with parsed validation errors
     * @param locale locale
     */
    private void parseValidationErrors(List<Map<String, String>> errors, BindingResult result, Locale locale) {
        for (Map<String, String> errorEntries : errors) {
            Map.Entry errorEntry = errorEntries.entrySet().iterator().next();
            ResourceBundle resourceBundle = ResourceBundle.getBundle("ValidationMessages", locale);
            if (!errorEntry.getKey().toString().isEmpty()) {
                Map.Entry<String, String> error = parseErrorCode(errorEntry.getKey().toString(), resourceBundle);
                if (error != null) {
                    result.rejectValue(error.getKey(), null, error.getValue());
                }
            }
        }
    }

    /**
     * Parse error code with specific {@link ResourceBundle}.
     *
     * @param errorCode error code
     * @param resourceBundle used {@link ResourceBundle}
     * @return parsed error as pair field - error message
     */
    private Map.Entry<String, String> parseErrorCode(String errorCode, ResourceBundle resourceBundle) {
        Map.Entry<String, String> error = null;
        if (resourceBundle.containsKey(errorCode)) {
            String errorMessage = resourceBundle.getString(errorCode);
            if (errorCode.contains("email")) {
                errorMessage = errorMessage.replace("{max}", String.valueOf(User.EMAIL_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("email", errorMessage);
            } else if (errorCode.contains("username")) {
                errorMessage = errorMessage.replace("{min}", String.valueOf(User.USERNAME_MIN_LENGTH))
                        .replace("{max}", String.valueOf(User.USERNAME_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("username", errorMessage);
            } else if (errorCode.contains("password")) {
                errorMessage = errorMessage.replace("{min}", String.valueOf(User.PASSWORD_MIN_LENGTH))
                        .replace("{max}", String.valueOf(User.PASSWORD_MAX_LENGTH));
                error = new HashMap.SimpleEntry<>("password", errorMessage);
            }
        }
        return error;
    }
}