package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.DepartmentOption;
import com.coshare.patientrecord.auth.dto.LoginAccountOptions;
import com.coshare.patientrecord.auth.dto.LoginOptions;
import com.coshare.patientrecord.auth.dto.LoginRequest;
import com.coshare.patientrecord.auth.dto.LoginResult;
import com.coshare.patientrecord.auth.dto.PasswordChangeRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.config.PortalMode;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalAuthenticationService {
    private final AuthSessionService medical;
    private final InventoryPortalAuthService inventory;
    private final PortalMode portalMode;

    public PortalAuthenticationService(AuthSessionService medical, InventoryPortalAuthService inventory, PortalMode portalMode) {
        this.medical = medical;
        this.inventory = inventory;
        this.portalMode = portalMode;
    }

    public LoginResult login(LoginRequest request, String remoteAddress) { return portalMode.isInventoryPortal() ? inventory.login(request, remoteAddress) : medical.login(request, remoteAddress); }
    public Optional<SessionUser> authenticate(String token) { return portalMode.isInventoryPortal() ? inventory.authenticate(token) : medical.authenticate(token); }
    public LoginOptions loginOptions() { return portalMode.isInventoryPortal() ? inventory.loginOptions() : medical.loginOptions(); }
    public LoginAccountOptions loginAccounts(String department) { return portalMode.isInventoryPortal() ? inventory.loginAccounts(department) : medical.loginAccounts(department); }
    public void logout(String token) { if (portalMode.isInventoryPortal()) inventory.logout(token); else medical.logout(token); }
    public void changePassword(SessionUser user, PasswordChangeRequest request) { if (portalMode.isInventoryPortal()) inventory.changePassword(user, request); else medical.changePassword(user, request); }
    public DepartmentOption switchActiveDepartment(String token, SessionUser user, String departmentId) {
        if (portalMode.isInventoryPortal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "进销存门户不支持切换科室");
        return medical.switchActiveDepartment(token, user, departmentId);
    }
}