/*
 * Copyright 2012-2014 TORCH GmbH
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.graylog2.rest.resources.system.inputs;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.graylog2.database.ValidationException;
import org.graylog2.inputs.Input;
import org.graylog2.inputs.InputImpl;
import org.graylog2.inputs.InputService;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationException;
import org.graylog2.plugin.inputs.InputState;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.rest.documentation.annotations.*;
import org.graylog2.rest.resources.RestResource;
import org.graylog2.security.RestPermissions;
import org.graylog2.shared.ServerStatus;
import org.graylog2.shared.inputs.InputRegistry;
import org.graylog2.shared.inputs.NoSuchInputTypeException;
import org.graylog2.shared.rest.resources.system.inputs.requests.InputLaunchRequest;
import org.graylog2.system.activities.Activity;
import org.graylog2.system.activities.ActivityWriter;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author Lennart Koopmann <lennart@torch.sh>
 */
@RequiresAuthentication
@Api(value = "System/Inputs", description = "Message inputs of this node")
@Path("/system/inputs")
public class InputsResource extends RestResource {

    private static final Logger LOG = LoggerFactory.getLogger(InputsResource.class);

    private final InputService inputService;
    private final InputRegistry inputRegistry;
    private final ActivityWriter activityWriter;

    @Inject
    public InputsResource(InputService inputService, InputRegistry inputRegistry, ActivityWriter activityWriter) {
        this.inputService = inputService;
        this.inputRegistry = inputRegistry;
        this.activityWriter = activityWriter;
    }

    @GET @Timed
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get information of a single input on this node")
    @Path("/{inputId}")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input on this node.")
    })
    public String single(@ApiParam(title = "inputId", required = true) @PathParam("inputId") String inputId) {
        checkPermission(RestPermissions.INPUTS_READ, inputId);

        MessageInput input = inputRegistry.getRunningInput(inputId);

        if (input == null) {
            LOG.info("Input [{}] not found. Returning HTTP 404.", inputId);
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        return json(input.asMap());

    }

    @GET @Timed
    @ApiOperation(value = "Get all inputs of this node")
    @Produces(MediaType.APPLICATION_JSON)
    public String list() {
        List<Map<String, Object>> inputStates = Lists.newArrayList();
        Map<String, Object> result = Maps.newHashMap();
        for (InputState inputState : inputRegistry.getInputStates()) {
			checkPermission(RestPermissions.INPUTS_READ, inputState.getMessageInput().getId());
            inputStates.add(inputState.asMap());
		}
        result.put("inputs", inputStates);
        result.put("total", inputStates.size());

        return json(result);
    }

    @POST @Timed
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Launch input on this node")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input type registered"),
            @ApiResponse(code = 400, message = "Missing or invalid configuration"),
            @ApiResponse(code = 400, message = "Type is exclusive and already has input running")
    })
    public Response create(@ApiParam(title = "JSON body", required = true) String body) throws ValidationException {
        checkPermission(RestPermissions.INPUTS_CREATE);

        InputLaunchRequest lr;
        try {
            lr = objectMapper.readValue(body, InputLaunchRequest.class);
        } catch(IOException e) {
            LOG.error("Error while parsing JSON", e);
            throw new BadRequestException(e);
        }

        // Build a proper configuration from POST data.
        Configuration inputConfig = new Configuration(lr.configuration);

        // Build input.
        DateTime createdAt = new DateTime(DateTimeZone.UTC);
        final MessageInput input;
        try {
            input = inputRegistry.create(lr.type);
            input.setTitle(lr.title);
            input.setGlobal(lr.global);
            input.setCreatorUserId(lr.creatorUserId);
            input.setCreatedAt(createdAt);

            input.checkConfiguration(inputConfig);
        } catch (NoSuchInputTypeException e) {
            LOG.error("There is no such input type registered.", e);
            throw new WebApplicationException(e, Response.Status.NOT_FOUND);
        } catch (ConfigurationException e) {
            LOG.error("Missing or invalid input configuration.", e);
            throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }

        // Don't run if exclusive and another instance is already running.
        if (input.isExclusive() && inputRegistry.hasTypeRunning(input.getClass())) {
            final String error = "Type is exclusive and already has input running.";
            LOG.error(error);
            throw new BadRequestException(error);
        }

        String inputId = UUID.randomUUID().toString();

        // Build MongoDB data
        Map<String, Object> inputData = Maps.newHashMap();
        inputData.put("input_id", inputId);
        inputData.put("title", lr.title);
        inputData.put("type", lr.type);
        inputData.put("creator_user_id", lr.creatorUserId);
        inputData.put("configuration", lr.configuration);
        inputData.put("created_at", createdAt);
        if (lr.global)
            inputData.put("global", true);
        else
            inputData.put("node_id", serverStatus.getNodeId().toString());

        // ... and check if it would pass validation. We don't need to go on if it doesn't.
        Input mongoInput = new InputImpl(inputData);

        // Persist input.
        String id;
        id = inputService.save(mongoInput);
        input.setPersistId(id);

        input.initialize(inputConfig);

        // Launch input. (this will run async and clean up itself in case of an error.)
        inputRegistry.launch(input, inputId);

        Map<String, Object> result = Maps.newHashMap();
        result.put("input_id", inputId);
        result.put("persist_id", id);

        return Response.status(Response.Status.ACCEPTED).entity(json(result)).build();
    }

    @GET @Timed
    @Path("/types")
    @ApiOperation(value = "Get all available input types of this node")
    @Produces(MediaType.APPLICATION_JSON)
    public String types() {
        Map<String, Object> result = Maps.newHashMap();
        result.put("types", inputRegistry.getAvailableInputs());

        return json(result);
    }

    @DELETE @Timed
    @Path("/{inputId}")
    @ApiOperation(value = "Terminate input on this node")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input on this node.")
    })
    public Response terminate(@ApiParam(title = "inputId", required = true) @PathParam("inputId") String inputId) {
        checkPermission(RestPermissions.INPUTS_TERMINATE, inputId);

        MessageInput input = inputRegistry.getRunningInput(inputId);

        if (input == null) {
            LOG.info("Cannot terminate input. Input not found.");
            throw new WebApplicationException(404);
        }

        String msg = "Attempting to terminate input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg);
        activityWriter.write(new Activity(msg, InputsResource.class));

        inputRegistry.terminate(input);

        if (serverStatus.hasCapability(ServerStatus.Capability.MASTER) || !input.getGlobal()) {
            // Remove from list and mongo.
            inputRegistry.cleanInput(input);
        }

        String msg2 = "Terminated input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg2);
        activityWriter.write(new Activity(msg2, InputsResource.class));

        return Response.status(Response.Status.ACCEPTED).build();
    }

    @POST @Timed
    @Path("/{inputId}/launch")
    @ApiOperation(value = "Launch existing input on this node")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input on this node.")
    })
    public Response launchExisting(@ApiParam(title = "inputId", required = true) @PathParam("inputId") String inputId) {
        final InputState inputState = inputRegistry.getInputState(inputId);

        if (inputState == null) {
            LOG.info("Cannot launch input. Input not found.");
            throw new WebApplicationException(404);
        }

        final MessageInput input = inputState.getMessageInput();

        if (input == null) {
            LOG.info("Cannot launch input. Input not found.");
            throw new WebApplicationException(404);
        }

        String msg = "Launching existing input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg);
        activityWriter.write(new Activity(msg, InputsResource.class));

        inputRegistry.launch(inputState);

        String msg2 = "Launched existing input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg2);
        activityWriter.write(new Activity(msg2, InputsResource.class));

        return Response.status(Response.Status.ACCEPTED).build();
    }

    @POST @Timed
    @Path("/{inputId}/stop")
    @ApiOperation(value = "Stop existing input on this node")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input on this node.")
    })
    public Response stop(@ApiParam(title = "inputId", required = true) @PathParam("inputId") String inputId) {
        final MessageInput input = inputRegistry.getRunningInput(inputId);
        if (input == null) {
            LOG.info("Cannot stop input. Input not found.");
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        String msg = "Stopping input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg);
        activityWriter.write(new Activity(msg, InputsResource.class));

        inputRegistry.stop(input);

        String msg2 = "Stopped input [" + input.getName()+ "]. Reason: REST request.";
        LOG.info(msg2);
        activityWriter.write(new Activity(msg2, InputsResource.class));

        return Response.status(Response.Status.ACCEPTED).build();
    }

    @POST @Timed
    @Path("/{inputId}/restart")
    @ApiOperation(value = "Restart existing input on this node")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input on this node.")
    })
    public Response restart(@ApiParam(title = "inputId", required = true) @PathParam("inputId") String inputId) {
        stop(inputId);
        launchExisting(inputId);
        return Response.status(Response.Status.ACCEPTED).build();
    }

    @GET @Timed
    @Path("/types/{inputType}")
    @ApiOperation(value = "Get information about a single input type")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No such input type registered.")
    })
    public String info(@ApiParam(title = "inputType", required = true) @PathParam("inputType") String inputType) {

        MessageInput input;
        try {
            input = inputRegistry.create(inputType);
        } catch (NoSuchInputTypeException e) {
            LOG.error("There is no such input type registered.", e);
            throw new WebApplicationException(e, Response.Status.NOT_FOUND);
        } catch (Exception e) {
            LOG.error("Unable to instantiate input of type <" + inputType + ">", e);
            throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
        }

        Map<String, Object> result = Maps.newHashMap();
        result.put("type", input.getClass().getCanonicalName());
        result.put("name", input.getName());
        result.put("is_exclusive", input.isExclusive());
        result.put("requested_configuration", input.getRequestedConfiguration().asList());
        result.put("link_to_docs", input.linkToDocs());

        return json(result);
    }

}
