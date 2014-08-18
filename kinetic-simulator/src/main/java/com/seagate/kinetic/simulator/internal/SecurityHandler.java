/**
 * 
 * Copyright (C) 2014 Seagate Technology.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */
package com.seagate.kinetic.simulator.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.seagate.kinetic.common.lib.HMACAlgorithmUtil;
import com.seagate.kinetic.common.lib.KineticMessage;
import com.seagate.kinetic.common.lib.RoleUtil;
import com.seagate.kinetic.proto.Kinetic.Command;
import com.seagate.kinetic.proto.Kinetic.Message;
import com.seagate.kinetic.proto.Kinetic.Command.MessageType;
import com.seagate.kinetic.proto.Kinetic.Command.Security;
import com.seagate.kinetic.proto.Kinetic.Command.Security.ACL;
import com.seagate.kinetic.proto.Kinetic.Command.Security.ACL.Permission;
import com.seagate.kinetic.proto.Kinetic.Command.Status.StatusCode;

/**
 * Security handler prototype.
 *
 * @author chiaming
 *
 */
public abstract class SecurityHandler {
    
    public static boolean checkPermission(KineticMessage request,
            KineticMessage respond, Map<Long, ACL> currentMap) {
        
        boolean hasPermission = false;
        
        Command.Builder commandBuilder = (Command.Builder) respond.getCommand();

        // set reply type
        commandBuilder.getHeaderBuilder()
        .setMessageType(MessageType.SECURITY_RESPONSE);
        
        // set ack sequence
        commandBuilder.getHeaderBuilder()
        .setAckSequence(request.getCommand().getHeader().getSequence());

        // check if has permission to set security
        if (currentMap == null) {
            hasPermission = true;
        } else {
            try {
                // check if client has permission
                Authorizer.checkPermission(currentMap, request.getMessage().getHmacAuth().getIdentity(), 
                        Permission.SECURITY);

                hasPermission = true;
            } catch (KVSecurityException e) {
                commandBuilder.getStatusBuilder()
                .setCode(StatusCode.NOT_AUTHORIZED);
                commandBuilder.getStatusBuilder()
                .setStatusMessage(e.getMessage());
            }
        }
        return hasPermission;

    }

    public static synchronized Map<Long, ACL> handleSecurity(
            KineticMessage request, KineticMessage response,
            Map<Long, ACL> currentMap, String kineticHome)
            throws KVStoreException, IOException {

        Command.Builder commandBuilder = (Command.Builder) response
                .getCommand();

        List<ACL> aclList = request.getCommand().getBody().getSecurity()
                .getAclList();

        // Validate input
        for (ACL acl : aclList) {
            // add algorithm check
            if (!acl.hasHmacAlgorithm()
                    || !HMACAlgorithmUtil.isSupported(acl.getHmacAlgorithm())) {
                commandBuilder.getStatusBuilder().setCode(
                        StatusCode.NO_SUCH_HMAC_ALGORITHM);
                return currentMap;
            }

            for (ACL.Scope domain : acl.getScopeList()) {
                if (domain.hasOffset() && domain.getOffset() < 0) {
                    // Negative offsets are not allowed
                    commandBuilder.getStatusBuilder().setCode(
                            StatusCode.INVALID_REQUEST);
                    commandBuilder.getStatusBuilder().setStatusMessage(
                            "Offset in domain is less than 0.");
                    return currentMap;
                }

                List<Permission> roleOfList = domain.getPermissionList();
                if (null == roleOfList || roleOfList.isEmpty()) {
                    commandBuilder.getStatusBuilder().setCode(
                            StatusCode.INVALID_REQUEST);
                    commandBuilder.getStatusBuilder().setStatusMessage(
                            "No role set in acl");
                    return currentMap;
                }

                for (Permission role : roleOfList) {
                    if (!RoleUtil.isValid(role)) {
                        commandBuilder.getStatusBuilder().setCode(
                                StatusCode.INVALID_REQUEST);
                        commandBuilder.getStatusBuilder().setStatusMessage(
                                "Role is invalid in acl. Role is: "
                                        + role.toString());
                        return currentMap;
                    }
                }
            }
        }

        for (ACL acl : aclList) {
            currentMap.put(acl.getIdentity(), acl);
        }

        SecurityHandler.persistAcl(request.getCommand().getBody().getSecurity()
                .toByteArray(), kineticHome);
        
        commandBuilder.getStatusBuilder().setCode(StatusCode.SUCCESS);

        return currentMap;
    }

    private static void persistAcl(byte[] contents, String kineticHome)
            throws IOException {
        String aclPersistFilePath = kineticHome + File.separator + ".acl";
        String aclPersistBakFilePath = aclPersistFilePath + ".bak";
        // delete backup file
        File aclBakFile = new File(aclPersistBakFilePath);
        if (aclBakFile.exists()) {
            aclBakFile.delete();
        }

        // backup file
        File aclFile = new File(aclPersistFilePath);
        aclFile.renameTo(aclBakFile);

        // save new file
        aclFile = new File(aclPersistFilePath);
        FileOutputStream out = new FileOutputStream(aclFile);
        out.write(contents);
        out.close();
    }

    public static Map<Long, ACL> loadACL(String kineticHome) throws IOException {
        String aclPersistFilePath = kineticHome + File.separator + ".acl";

        File aclFile = new File(aclPersistFilePath);
        Map<Long, ACL> aclMap = new HashMap<Long, ACL>();
        if (aclFile.exists()) {
            Long fileLength = aclFile.length();
            if (fileLength != 0) {
                byte[] fileContent = new byte[fileLength.intValue()];
                FileInputStream in = new FileInputStream(aclFile);
                in.read(fileContent);
                in.close();
                Security security = Security.parseFrom(fileContent);
                List<ACL> aclList = security.getAclList();

                for (ACL acl : aclList) {
                    aclMap.put(acl.getIdentity(), acl);
                }
            }
        }
        return aclMap;
    }
}
