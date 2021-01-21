/*
 * Copyright (c) Microsoft Corporation.
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

package com.microsoft.playwright.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.file.FileSystems;
import java.util.*;

import static java.util.Arrays.asList;

abstract class Element {
  final String jsonName;
  final String jsonPath;
  final JsonElement jsonElement;
  final Element parent;

  Element(Element parent, JsonElement jsonElement) {
    this(parent, false, jsonElement);
  }

  Element(Element parent, boolean useParentJsonPath, JsonElement jsonElement) {
    this.parent = parent;
    if (jsonElement != null && jsonElement.isJsonObject()) {
      this.jsonName = jsonElement.getAsJsonObject().get("name").getAsString();
    } else {
      this.jsonName = "";
    }
    if (useParentJsonPath) {
      this.jsonPath = parent.jsonPath;
    } else {
      this.jsonPath = parent == null ? jsonName : parent.jsonPath + "." + jsonName ;
    }
    this.jsonElement = jsonElement;
  }


  TypeDefinition typeScope() {
    return parent.typeScope();
  }

  static String toTitle(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  void writeJavadoc(List<String> output, String offset, String text) {
    if (text.isEmpty()) {
      return;
    }
    output.add(offset + "/**");
    String[] lines = text.split("\\n");
    for (String line : lines) {
      output.add((offset + " *" + (line.isEmpty() ? "" : " ") + line)
        .replace("*/", "*\\/")
        .replace("**NOTE**", "<strong>NOTE</strong>")
        .replaceAll("`([^`]+)`", "{@code $1}"));
    }
    output.add(offset + " */");
  }

  String formattedComment() {
    return comment()
      // Remove any code snippets between ``` and ```.
      .replaceAll("\\n```((?<!`)`(?!`)|[^`])+```\\n", "")
      .replaceAll("\\nAn example of[^\\n]+\\n", "")
      .replaceAll("\\nThis example [^\\n]+\\n", "")
      .replaceAll("\\nExamples:\\n", "")
      .replaceAll("\\nSee ChromiumBrowser[^\\n]+", "\n")
      // > **NOTE** ... => **NOTE** ...
      .replaceAll("^>", "")
      .replaceAll("\\n\\n", "\n\n<p> ");
  }

  String comment() {
    JsonObject json = jsonElement.getAsJsonObject();
    if (!json.has("comment")) {
      return "";
    }
    return json.get("comment").getAsString();
  }
}

// Represents return type of a method, type of a method param or type of a field.
class TypeRef extends Element {
  String customType;
  boolean isNestedClass;

  TypeRef(Element parent, JsonElement jsonElement) {
    super(parent, true, jsonElement);

    createCustomType();
  }

  enum GeneratedType { ENUM, CLASS, OTHER };
  private static GeneratedType generatedTypeFor(JsonObject jsonType) {
    switch (jsonType.get("name").getAsString()) {
      case "union": {
        for (JsonElement item : jsonType.getAsJsonArray("union")) {
          String valueName = item.getAsJsonObject().get("name").getAsString();
          if ("null".equals(valueName)) {
            continue;
          }
          if (valueName.startsWith("\"")) {
            continue;
          }
          if (valueName.equals("Object")) {
            return GeneratedType.CLASS;
          }
          // If a value is not null and not a string it is a class name.
          return GeneratedType.OTHER;
        }
        return GeneratedType.ENUM;
      }
      case "Object": {
        return GeneratedType.CLASS;
      }
      case "Array":
      case "Promise": {
        for (JsonElement item : jsonType.getAsJsonArray("templates")) {
          return generatedTypeFor(item.getAsJsonObject());
        }
        return GeneratedType.OTHER;
      }
      default:
        return GeneratedType.OTHER;
    }
  }

  private static String typeExpression(JsonObject jsonType) {
    String typeName = jsonType.get("name").getAsString();
    if ("union".equals(typeName)) {
      List<String> values = new ArrayList<>();
      for (JsonElement item : jsonType.getAsJsonArray("union")) {
        values.add(typeExpression(item.getAsJsonObject()));
      }
      values.sort(String::compareTo);
      return String.join("|", values);
    }
    if ("function".equals(typeName)) {
      if (!jsonType.has("args")) {
        return typeName;
      }
      List<String> args = new ArrayList<>();
      for (JsonElement item : jsonType.getAsJsonArray("args")) {
        args.add(typeExpression(item.getAsJsonObject()));
      }
      String returnType = "";
      if (jsonType.has("returnType") && jsonType.get("returnType").isJsonObject()) {
        returnType = ":" + typeExpression(jsonType.getAsJsonObject("returnType"));
      }
      return typeName + "(" + String.join(", ", args) + ")" + returnType;
    }
    List<String> templateArgs = new ArrayList<>();
    if (jsonType.has("templates")) {
      for (JsonElement item : jsonType.getAsJsonArray("templates")) {
        templateArgs.add(typeExpression(item.getAsJsonObject()));
      }
    }
    if (templateArgs.isEmpty()) {
      return typeName;
    }
    return typeName + "<" + String.join(", ", templateArgs) + ">";
  }

  void createCustomType() {
    GeneratedType generatedType = generatedTypeFor(jsonElement.getAsJsonObject());
    // Use path to the corresponding method, param of field as the key.
    String parentPath = parent.jsonPath;
    Types.Mapping mapping = TypeDefinition.types.findForPath(parentPath);
    if (mapping == null) {
      if (generatedType == GeneratedType.ENUM) {
        throw new RuntimeException("Cannot create enum, type mapping is missing for: " + parentPath);
      }
      if (generatedType != GeneratedType.CLASS) {
        return;
      }

      if (parent instanceof Field) {
        customType = toTitle(parent.jsonName);
      } else {
//        String typeExpression = typeExpression(jsonElement.getAsJsonObject());
//        System.out.println("add(\"" + parentPath + "\", \"" + typeExpression + "\", \"" + typeExpression + "\");" );
        customType = toTitle(parent.parent.jsonName) + toTitle(parent.jsonName);
      }
    } else {
      String typeExpression = typeExpression(jsonElement.getAsJsonObject());
      if (!mapping.from.equals(typeExpression)) {
        throw new RuntimeException("Unexpected source type for: " + parentPath +". Expected: " + mapping.from + "; found: " + typeExpression);
      }
      customType = mapping.to;
      if (mapping.customMapping != null) {
        mapping.customMapping.defineTypesIn(typeScope());
        return;
      }
    }
    if (generatedType == GeneratedType.ENUM) {
      typeScope().createEnum(customType, jsonElement.getAsJsonObject());
    } else if (generatedType == GeneratedType.CLASS) {
      typeScope().createNestedClass(customType, this, jsonElement.getAsJsonObject());
      isNestedClass = true;
    }
  }

  String toJava() {
    if (customType != null) {
      return customType;
    }
    if (jsonElement.isJsonNull()) {
      return "void";
    }
    // Convert optional fields to boxed types.
    if (!parent.jsonElement.getAsJsonObject().get("required").getAsBoolean()) {
      if (jsonName.equals("int")) {
        return "Integer";
      }
      if (jsonName.equals("float")) {
        return "Double";
      }
      if (jsonName.equals("boolean")) {
        return "Boolean";
      }
    }
    if (jsonName.replace("null|", "").contains("|")) {
      throw new RuntimeException("Missing mapping for type union: " + jsonPath + ": " + jsonName);
    }
//    System.out.println(jsonPath + " : " + jsonName);
//    if (jsonName.equals("Promise")) {
//      System.out.println(jsonElement);
//    }
    return convertBuiltinType(jsonElement.getAsJsonObject());
  }

  private static String convertBuiltinType(JsonObject jsonType) {
    String name = jsonType.get("name").getAsString();
    if ("int".equals(name)) {
      return "int";
    }
    if ("float".equals(name)) {
      return "double";
    }
    if ("string".equals(name)) {
      return "String";
    }
    if ("void".equals(name)) {
      return "void";
    }
    if ("Array".equals(name)) {
      return "List<" + convertTemplateParams(jsonType) + ">";
    }
    if ("Map".equals(name)) {
      return "Map<" + convertTemplateParams(jsonType) + ">";
    }
    if ("Promise".equals(name)) {
      return convertTemplateParams(jsonType);
    }
    if ("function".equals(name)) {
      throw new RuntimeException("Missing mapping for " + jsonType);
    }
    return name;
  }

  private static String convertTemplateParams(JsonObject jsonType) {
    if (!jsonType.has("templates")) {
      return "";
    }
    List<String> params = new ArrayList<>();
    for (JsonElement item : jsonType.getAsJsonArray("templates")) {
      params.add(convertBuiltinType(item.getAsJsonObject()));
    }
    return String.join(", ", params);
  }
}

abstract class TypeDefinition extends Element {
  final List<Enum> enums = new ArrayList<>();
  final List<NestedClass> classes = new ArrayList<>();

  static final Types types = new Types();

  TypeDefinition(Element parent, JsonObject jsonElement) {
    super(parent, jsonElement);
  }

  TypeDefinition(Element parent, boolean useParentJsonPath, JsonObject jsonElement) {
    super(parent, useParentJsonPath, jsonElement);
  }

  @Override
  TypeDefinition typeScope() {
    return this;
  }

  void createEnum(String name, JsonObject jsonObject) {
    addEnum(new Enum(this, name, jsonObject));
  }

  void addEnum(Enum newEnum) {
    for (Enum e : enums) {
      if (e.name.equals(newEnum.name)) {
        return;
      }
    }
    enums.add(newEnum);
  }

  void createNestedClass(String name, Element parent, JsonObject jsonObject) {
    for (NestedClass c : classes) {
      if (c.name.equals(name)) {
        return;
      }
    }
    classes.add(new NestedClass(parent, name, jsonObject));
  }

  void writeTo(List<String> output, String offset) {
    for (Enum e : enums) {
      e.writeTo(output, offset);
    }
    for (NestedClass c : classes) {
      c.writeTo(output, offset);
    }
  }
}

class Event extends Element {
  private static Map<String, String> eventNames = new HashMap<>();
  static {
    eventNames.put("Browser.disconnected", "Disconnected");

    eventNames.put("BrowserContext.close", "Close");
    eventNames.put("BrowserContext.page", "Page");

    eventNames.put("Page.close", "Close");
    eventNames.put("Page.console", "Console");
    eventNames.put("Page.crash", "Crash");
    eventNames.put("Page.dialog", "Dialog");
    eventNames.put("Page.domcontentloaded", "DomContentLoaded");
    eventNames.put("Page.download", "Download");
    eventNames.put("Page.filechooser", "FileChooser");
    eventNames.put("Page.frameattached", "FrameAttached");
    eventNames.put("Page.framedetached", "FrameDetached");
    eventNames.put("Page.framenavigated", "FrameNavigated");
    eventNames.put("Page.load", "Load");
    eventNames.put("Page.pageerror", "PageError");
    eventNames.put("Page.popup", "Popup");
    eventNames.put("Page.request", "Request");
    eventNames.put("Page.requestfailed", "RequestFailed");
    eventNames.put("Page.requestfinished", "RequestFinished");
    eventNames.put("Page.response", "Response");
    eventNames.put("Page.websocket", "WebSocket");
    eventNames.put("Page.worker", "Worker");

    eventNames.put("WebSocket.close", "Close");
    eventNames.put("WebSocket.framereceived", "FrameReceived");
    eventNames.put("WebSocket.framesent", "FrameSent");
    eventNames.put("WebSocket.socketerror", "SocketError");

    eventNames.put("Worker.close", "Close");
  }

  private static Set<String> waitForEvents = new HashSet<>();
  static {
    waitForEvents.add("BrowserContext.page");

    waitForEvents.add("Page.close");
    waitForEvents.add("Page.console");
    waitForEvents.add("Page.download");
    waitForEvents.add("Page.filechooser");
    waitForEvents.add("Page.frameattached");
    waitForEvents.add("Page.framedetached");
    waitForEvents.add("Page.framenavigated");
    waitForEvents.add("Page.pageerror");
    waitForEvents.add("Page.popup");
    waitForEvents.add("Page.request");
    waitForEvents.add("Page.requestfailed");
    waitForEvents.add("Page.requestfinished");
    waitForEvents.add("Page.response");
    waitForEvents.add("Page.websocket");
    waitForEvents.add("Page.worker");

    waitForEvents.add("WebSocket.framereceived");
    waitForEvents.add("WebSocket.framesent");
    waitForEvents.add("WebSocket.socketerror");

    waitForEvents.add("Worker.close");
  }

  private final TypeRef type;

  Event(Element parent, JsonObject jsonElement) {
    super(parent, jsonElement);
    type = new TypeRef(this, jsonElement.get("type"));
  }

  void writeListenerMethods(List<String> output, String offset) {
    if (!eventNames.containsKey(jsonPath)) {
      throw new RuntimeException("Unknown event: " + jsonPath);
    }
    String name = eventNames.get(jsonPath);
    String listenerType = "void".equals(type.toJava()) ? "Runnable" : "Consumer<" + type.toJava() + ">";
    output.add(offset + "void on" + name + "(" + listenerType + " handler);");
    output.add(offset + "void off" + name + "(" + listenerType + " handler);");
  }

  void writeWaitForEventIfNeeded(List<String> output, String offset) {
    if (!waitForEvents.contains(jsonPath)) {
      return;
    }
    String name = eventNames.get(jsonPath);
    String methodName = "waitFor" + name;
    // Skip events for which there is waitFor* method in the upstream API, that method will generate the code.
    if (Method.waitForMethods.contains(parent.jsonPath + "." + methodName)) {
      return;
    }
    output.add("");
    String optionsClass = toTitle(methodName) + "Options";
    output.add(offset + "class " + optionsClass + " {");
    output.add(offset + "  public Double timeout;");
    output.add(offset + "  public " + optionsClass + " withTimeout(double timeout) {");
    output.add(offset + "    this.timeout = timeout;");
    output.add(offset + "    return this;");
    output.add(offset + "  }");
    output.add(offset + "}");
    String paramType = jsonPath.equals("Page.close") ? "Page" : type.toJava();
    output.add(offset + paramType + " " + methodName + "(Runnable code, " + optionsClass + " options);");
    output.add(offset + "default " + paramType + " " + methodName + "(Runnable code) { return " + methodName + "(code, null); }");
  }
}

class Method extends Element {
  final TypeRef returnType;
  final List<Param> params = new ArrayList<>();
  private final String name;

  private static Map<String, String> tsToJavaMethodName = new HashMap<>();
  static {
    tsToJavaMethodName.put("continue", "continue_");
    tsToJavaMethodName.put("$eval", "evalOnSelector");
    tsToJavaMethodName.put("$$eval", "evalOnSelectorAll");
    tsToJavaMethodName.put("$", "querySelector");
    tsToJavaMethodName.put("$$", "querySelectorAll");
    tsToJavaMethodName.put("goto", "navigate");
  }

  static Set<String> waitForMethods = new HashSet<>();
  static {
    waitForMethods.add("Page.waitForNavigation");
    waitForMethods.add("Page.waitForRequest");
    waitForMethods.add("Page.waitForResponse");
    waitForMethods.add("Frame.waitForNavigation");
  }

  private static Map<String, String[]> customSignature = new HashMap<>();
  static {
    customSignature.put("Page.setViewportSize", new String[]{"void setViewportSize(int width, int height);"});
    // The method is deprecated in ts, just remove it in Java.
    customSignature.put("BrowserContext.setHTTPCredentials", new String[0]);
    // No connect for now.
    customSignature.put("BrowserType.connect", new String[0]);
    customSignature.put("BrowserType.launchServer", new String[0]);
    // We don't expose Chromium-specific APIs at the moment.
    customSignature.put("Page.coverage", new String[0]);
    customSignature.put("BrowserContext.route", new String[]{
      "void route(String url, Consumer<Route> handler);",
      "void route(Pattern url, Consumer<Route> handler);",
      "void route(Predicate<String> url, Consumer<Route> handler);",
    });
    // There is no standard JSON type in Java.
    customSignature.put("Response.json", new String[0]);
    customSignature.put("Request.postDataJSON", new String[0]);
    customSignature.put("Page.frame", new String[]{
      "Frame frameByName(String name);",
      "Frame frameByUrl(String glob);",
      "Frame frameByUrl(Pattern pattern);",
      "Frame frameByUrl(Predicate<String> predicate);",
    });
    customSignature.put("Page.route", new String[]{
      "void route(String url, Consumer<Route> handler);",
      "void route(Pattern url, Consumer<Route> handler);",
      "void route(Predicate<String> url, Consumer<Route> handler);",
    });
    customSignature.put("BrowserContext.unroute", new String[]{
      "default void unroute(String url) { unroute(url, null); }",
      "default void unroute(Pattern url) { unroute(url, null); }",
      "default void unroute(Predicate<String> url) { unroute(url, null); }",
      "void unroute(String url, Consumer<Route> handler);",
      "void unroute(Pattern url, Consumer<Route> handler);",
      "void unroute(Predicate<String> url, Consumer<Route> handler);",
    });
    customSignature.put("Page.unroute", new String[]{
      "default void unroute(String url) { unroute(url, null); }",
      "default void unroute(Pattern url) { unroute(url, null); }",
      "default void unroute(Predicate<String> url) { unroute(url, null); }",
      "void unroute(String url, Consumer<Route> handler);",
      "void unroute(Pattern url, Consumer<Route> handler);",
      "void unroute(Predicate<String> url, Consumer<Route> handler);",
    });
    customSignature.put("BrowserContext.cookies", new String[]{
      "default List<Cookie> cookies() { return cookies((List<String>) null); }",
      "default List<Cookie> cookies(String url) { return cookies(Arrays.asList(url)); }",
      "List<Cookie> cookies(List<String> urls);",
    });
    customSignature.put("BrowserContext.addCookies", new String[]{
      "void addCookies(List<AddCookie> cookies);"
    });
    customSignature.put("FileChooser.setFiles", new String[]{
      "default void setFiles(Path file) { setFiles(file, null); }",
      "default void setFiles(Path file, SetFilesOptions options) { setFiles(new Path[]{ file }, options); }",
      "default void setFiles(Path[] files) { setFiles(files, null); }",
      "void setFiles(Path[] files, SetFilesOptions options);",
      "default void setFiles(FileChooser.FilePayload file) { setFiles(file, null); }",
      "default void setFiles(FileChooser.FilePayload file, SetFilesOptions options)  { setFiles(new FileChooser.FilePayload[]{ file }, options); }",
      "default void setFiles(FileChooser.FilePayload[] files) { setFiles(files, null); }",
      "void setFiles(FileChooser.FilePayload[] files, SetFilesOptions options);",
    });
    customSignature.put("ElementHandle.setInputFiles", new String[]{
      "default void setInputFiles(Path file) { setInputFiles(file, null); }",
      "default void setInputFiles(Path file, SetInputFilesOptions options) { setInputFiles(new Path[]{ file }, options); }",
      "default void setInputFiles(Path[] files) { setInputFiles(files, null); }",
      "void setInputFiles(Path[] files, SetInputFilesOptions options);",
      "default void setInputFiles(FileChooser.FilePayload file) { setInputFiles(file, null); }",
      "default void setInputFiles(FileChooser.FilePayload file, SetInputFilesOptions options)  { setInputFiles(new FileChooser.FilePayload[]{ file }, options); }",
      "default void setInputFiles(FileChooser.FilePayload[] files) { setInputFiles(files, null); }",
      "void setInputFiles(FileChooser.FilePayload[] files, SetInputFilesOptions options);",
    });
    String[] setInputFilesWithSelector = {
      "default void setInputFiles(String selector, Path file) { setInputFiles(selector, file, null); }",
      "default void setInputFiles(String selector, Path file, SetInputFilesOptions options) { setInputFiles(selector, new Path[]{ file }, options); }",
      "default void setInputFiles(String selector, Path[] files) { setInputFiles(selector, files, null); }",
      "void setInputFiles(String selector, Path[] files, SetInputFilesOptions options);",
      "default void setInputFiles(String selector, FileChooser.FilePayload file) { setInputFiles(selector, file, null); }",
      "default void setInputFiles(String selector, FileChooser.FilePayload file, SetInputFilesOptions options)  { setInputFiles(selector, new FileChooser.FilePayload[]{ file }, options); }",
      "default void setInputFiles(String selector, FileChooser.FilePayload[] files) { setInputFiles(selector, files, null); }",
      "void setInputFiles(String selector, FileChooser.FilePayload[] files, SetInputFilesOptions options);",
    };
    customSignature.put("Page.setInputFiles", setInputFilesWithSelector);
    customSignature.put("Frame.setInputFiles", setInputFilesWithSelector);

    // We only have typed onPage/onDownload/... event listeners in Java.
    customSignature.put("Page.waitForEvent", new String[] {});
    customSignature.put("BrowserContext.waitForEvent", new String[] {});
    customSignature.put("WebSocket.waitForEvent", new String[] {});

    customSignature.put("Page.waitForRequest", new String[] {
      "Request waitForRequest(Runnable code);",
      "default Request waitForRequest(Runnable code, String urlGlob) { return waitForRequest(code, urlGlob, null); }",
      "default Request waitForRequest(Runnable code, Pattern urlPattern) { return waitForRequest(code, urlPattern, null); }",
      "default Request waitForRequest(Runnable code, Predicate<String> urlPredicate) { return waitForRequest(code, urlPredicate, null); }",
      "Request waitForRequest(Runnable code, String urlGlob, WaitForRequestOptions options);",
      "Request waitForRequest(Runnable code, Pattern urlPattern, WaitForRequestOptions options);",
      "Request waitForRequest(Runnable code, Predicate<String> urlPredicate, WaitForRequestOptions options);"
    });
    customSignature.put("Page.waitForResponse", new String[] {
      "Response waitForResponse(Runnable code);",
      "default Response waitForResponse(Runnable code, String urlGlob) { return waitForResponse(code, urlGlob, null); }",
      "default Response waitForResponse(Runnable code, Pattern urlPattern) { return waitForResponse(code, urlPattern, null); }",
      "default Response waitForResponse(Runnable code, Predicate<String> urlPredicate) { return waitForResponse(code, urlPredicate, null); }",
      "Response waitForResponse(Runnable code, String urlGlob, WaitForResponseOptions options);",
      "Response waitForResponse(Runnable code, Pattern urlPattern, WaitForResponseOptions options);",
      "Response waitForResponse(Runnable code, Predicate<String> urlPredicate, WaitForResponseOptions options);"
    });

    String[] waitForNavigation = {
      "default Response waitForNavigation(Runnable code) { return waitForNavigation(code, null); }",
      "Response waitForNavigation(Runnable code, WaitForNavigationOptions options);"
    };
    customSignature.put("Frame.waitForNavigation", waitForNavigation);
    customSignature.put("Page.waitForNavigation", waitForNavigation);

    String[] selectOption = {
      "default List<String> selectOption(String selector, String value) {",
      "  return selectOption(selector, value, null);",
      "}",
      "default List<String> selectOption(String selector, String value, SelectOptionOptions options) {",
      "  String[] values = value == null ? null : new String[]{ value };",
      "  return selectOption(selector, values, options);",
      "}",
      "default List<String> selectOption(String selector, String[] values) {",
      "  return selectOption(selector, values, null);",
      "}",
      "default List<String> selectOption(String selector, String[] values, SelectOptionOptions options) {",
      "  if (values == null) {",
      "    return selectOption(selector, new ElementHandle.SelectOption[0], options);",
      "  }",
      "  return selectOption(selector, Arrays.asList(values).stream().map(",
      "    v -> new ElementHandle.SelectOption().withValue(v)).toArray(ElementHandle.SelectOption[]::new), options);",
      "}",
      "default List<String> selectOption(String selector, ElementHandle.SelectOption value) {",
      "  return selectOption(selector, value, null);",
      "}",
      "default List<String> selectOption(String selector, ElementHandle.SelectOption value, SelectOptionOptions options) {",
      "  ElementHandle.SelectOption[] values = value == null ? null : new ElementHandle.SelectOption[]{value};",
      "  return selectOption(selector, values, options);",
      "}",
      "default List<String> selectOption(String selector, ElementHandle.SelectOption[] values) {",
      "  return selectOption(selector, values, null);",
      "}",
      "List<String> selectOption(String selector, ElementHandle.SelectOption[] values, SelectOptionOptions options);",
      "default List<String> selectOption(String selector, ElementHandle value) {",
      "  return selectOption(selector, value, null);",
      "}",
      "default List<String> selectOption(String selector, ElementHandle value, SelectOptionOptions options) {",
      "  ElementHandle[] values = value == null ? null : new ElementHandle[]{value};",
      "  return selectOption(selector, values, options);",
      "}",
      "default List<String> selectOption(String selector, ElementHandle[] values) {",
      "  return selectOption(selector, values, null);",
      "}",
      "List<String> selectOption(String selector, ElementHandle[] values, SelectOptionOptions options);",
    };
    customSignature.put("Page.selectOption", selectOption);
    customSignature.put("Frame.selectOption", selectOption);
    customSignature.put("ElementHandle.selectOption", Arrays.stream(selectOption).map(s -> s
      .replace("String selector, ", "")
      .replace("(selector, ", "(")
      .replace("ElementHandle.", "")).toArray(String[]::new));

    customSignature.put("Selectors.register", new String[] {
      "default void register(String name, String script) { register(name, script, null); }",
      "void register(String name, String script, RegisterOptions options);",
      "default void register(String name, Path path) { register(name, path, null); }",
      "void register(String name, Path path, RegisterOptions options);"
    });
  }

  private static Set<String> skipJavadoc = new HashSet<>(asList(
    "BrowserContext.waitForEvent.optionsOrPredicate",
    "Page.waitForEvent.optionsOrPredicate",
    "WebSocket.waitForEvent.optionsOrPredicate",
    "Page.frame.options",
    "Page.waitForRequest",
    "Page.waitForResponse"
    ));

  Method(TypeDefinition parent, JsonObject jsonElement) {
    super(parent, jsonElement);
    if (customSignature.containsKey(jsonPath) && customSignature.get(jsonPath).length == 0) {
      returnType = null;
    } else {
      returnType = new TypeRef(this, jsonElement.get("type"));
      if (jsonElement.has("args")) {
        for (JsonElement arg : jsonElement.getAsJsonArray("args")) {
          params.add(new Param(this, arg.getAsJsonObject()));
        }
      }
    }
    name = tsToJavaMethodName.containsKey(jsonName) ? tsToJavaMethodName.get(jsonName) : jsonName;
  }

  private String toJava() {
    StringBuilder paramList = new StringBuilder();
    for (Param p : params) {
      if (paramList.length() > 0)
        paramList.append(", ");
      paramList.append(p.toJava());
    }

    return returnType.toJava() + " " + name + "(" + paramList + ");";
  }

  void writeTo(List<String> output, String offset) {
    if (customSignature.containsKey(jsonPath)) {
      String[] signatures = customSignature.get(jsonPath);
      for (int i = 0; i < signatures.length; i++) {
        if (i == signatures.length - 1) {
          writeJavadoc(output, offset);
        }
        output.add(offset + signatures[i]);
      }
      return;
    }
    for (int i = params.size() - 1; i >= 0; i--) {
      Param p = params.get(i);
      if (!p.isOptional()) {
        break;
      }
      writeDefaultOverloadedMethod(i, output, offset);
    }
    writeJavadoc(output, offset);
    output.add(offset + toJava());
  }

  private void writeDefaultOverloadedMethod(int paramCount, List<String> output, String offset) {
    StringBuilder paramList = new StringBuilder();
    StringBuilder argList = new StringBuilder();
    for (int i = 0; i < paramCount; i++) {
      Param p = params.get(i);
      if (paramList.length() > 0) {
        paramList.append(", ");
        argList.append(", ");
      }
      paramList.append(p.toJava());
      argList.append(p.jsonName);
    }
    if (argList.length() > 0) {
      argList.append(", ");
    }
    argList.append("int".equals(params.get(paramCount).type.toJava()) ? "0" : "null");
    String returns = returnType.toJava().equals("void") ? "" : "return ";
    output.add(offset + "default " + returnType.toJava() + " " + name + "(" + paramList + ") {");
    output.add(offset + "  " + returns + name + "(" + argList + ");");
    output.add(offset + "}");
  }

  private void writeJavadoc(List<String> output, String offset) {
    if (skipJavadoc.contains(jsonPath)) {
      return;
    }
    List<String> sections = new ArrayList<>();
    sections.add(formattedComment());
    boolean hasBlankLine = false;
    if (!params.isEmpty()) {
      for (Param p : params) {
        String comment = p.comment();
        if (comment.isEmpty()) {
          continue;
        }
        if (skipJavadoc.contains(p.jsonPath)) {
          continue;
        }
        if (!hasBlankLine) {
          sections.add("");
          hasBlankLine = true;
        }
        sections.add("@param " + p.name() + " " + comment);
      }
    }
    if (jsonElement.getAsJsonObject().has("returnComment")) {
      if (!hasBlankLine) {
        sections.add("");
        hasBlankLine = true;
      }
      String returnComment = jsonElement.getAsJsonObject().get("returnComment").getAsString();
      sections.add("@return " + returnComment);
    }
    writeJavadoc(output, offset, String.join("\n", sections));
  }
}

class Param extends Element {
  final TypeRef type;

  private static Map<String, String> customName = new HashMap<>();
  static {
    customName.put("Keyboard.type.options", "delay");
    customName.put("Keyboard.press.options", "delay");
  }

  Param(Method method, JsonObject jsonElement) {
    super(method, jsonElement);
    type = new TypeRef(this, jsonElement.get("type").getAsJsonObject());
  }

  boolean isOptional() {
    return !jsonElement.getAsJsonObject().get("required").getAsBoolean();
  }

  String name() {
    String name = customName.get(jsonPath);
    if (name != null) {
      return name;
    }
    return jsonName;
  }

  String toJava() {
    return type.toJava() + " " + name();
  }
}

class Field extends Element {
  final String name;
  final TypeRef type;

  Field(NestedClass parent, String name, JsonObject jsonElement) {
    super(parent, jsonElement);
    this.name = name;
    this.type = new TypeRef(this, jsonElement.getAsJsonObject().get("type"));
  }

  void writeTo(List<String> output, String offset, String access) {
    writeJavadoc(output, offset, comment());
    if (asList("Frame.waitForNavigation.options.url",
               "Page.waitForNavigation.options.url").contains(jsonPath)) {
      output.add(offset + "public String glob;");
      output.add(offset + "public Pattern pattern;");
      output.add(offset + "public Predicate<String> predicate;");
      return;
    }
    if (asList("Frame.waitForFunction.options.polling",
               "Page.waitForFunction.options.polling").contains(jsonPath)) {
      output.add(offset + "public Integer pollingInterval;");
      return;
    }
    if ("Route.fulfill.response.body".equals(jsonPath)) {
      output.add(offset + "public String body;");
      output.add(offset + "public byte[] bodyBytes;");
      return;
    }
    if (asList("Page.emulateMedia.params.media",
               "Page.emulateMedia.params.colorScheme").contains(jsonPath)) {
      output.add(offset + access + "Optional<" + type.toJava() + "> " + name + ";");
      return;
    }
    if (asList("Browser.newContext.options.storageState",
               "Browser.newPage.options.storageState").contains(jsonPath)) {
      output.add(offset + access + type.toJava() + " " + name + ";");
      output.add(offset + access + "Path " + name + "Path;");
      return;
    }
    if (asList("BrowserType.launch.options.ignoreDefaultArgs",
               "BrowserType.launchPersistentContext.options.ignoreDefaultArgs").contains(jsonPath)) {
      output.add(offset + access + "List<String> ignoreDefaultArgs;");
      output.add(offset + access + "Boolean ignoreAllDefaultArgs;");
      return;
    }
    output.add(offset + access + type.toJava() + " " + name + ";");
  }

  void writeGetter(List<String> output, String offset) {
    output.add(offset + "public " + type.toJava() + " " + name + "() {");
    output.add(offset + "  return this." + name + ";");
    output.add(offset + "}");
  }

  void writeBuilderMethod(List<String> output, String offset, String parentClass) {
    if (asList("Frame.waitForNavigation.options.url",
               "Page.waitForNavigation.options.url").contains(jsonPath)) {
      output.add(offset + "public WaitForNavigationOptions withUrl(String glob) {");
      output.add(offset + "  this.glob = glob;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForNavigationOptions withUrl(Pattern pattern) {");
      output.add(offset + "  this.pattern = pattern;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForNavigationOptions withUrl(Predicate<String> predicate) {");
      output.add(offset + "  this.predicate = predicate;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (asList("Frame.waitForFunction.options.polling",
               "Page.waitForFunction.options.polling").contains(jsonPath)) {
      output.add(offset + "public WaitForFunctionOptions withRequestAnimationFrame() {");
      output.add(offset + "  this.pollingInterval = null;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForFunctionOptions withPollingInterval(int millis) {");
      output.add(offset + "  this.pollingInterval = millis;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (asList("Page.click.options.position",
      "Page.dblclick.options.position",
      "Page.hover.options.position",
      "Frame.click.options.position",
      "Frame.dblclick.options.position",
      "Frame.hover.options.position",
      "ElementHandle.click.options.position",
      "ElementHandle.dblclick.options.position",
      "ElementHandle.hover.options.position").contains(jsonPath)) {
      output.add(offset + "public " + parentClass + " withPosition(Position position) {");
      output.add(offset + "  this.position = position;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public " + parentClass + " withPosition(int x, int y) {");
      output.add(offset + "  return withPosition(new Position(x, y));");
      output.add(offset + "}");
      return;
    }
    if (asList("Page.emulateMedia.params.media",
               "Page.emulateMedia.params.colorScheme").contains(jsonPath)) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(" + type.toJava() + " " + name + ") {");
      output.add(offset + "  this." + name + " = Optional.ofNullable(" + name + ");");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (asList("BrowserType.launch.options.ignoreDefaultArgs",
               "BrowserType.launchPersistentContext.options.ignoreDefaultArgs").contains(jsonPath)) {
      output.add(offset + "public " + parentClass + " withIgnoreDefaultArgs(List<String> argumentNames) {");
      output.add(offset + "  this.ignoreDefaultArgs = argumentNames;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public " + parentClass + " withIgnoreAllDefaultArgs(boolean ignore) {");
      output.add(offset + "  this.ignoreAllDefaultArgs = ignore;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (asList("Browser.newContext.options.storageState",
               "Browser.newPage.options.storageState").contains(jsonPath)) {
      output.add(offset + "public " + parentClass + " withStorageState(BrowserContext.StorageState storageState) {");
      output.add(offset + "  this.storageState = storageState;");
      output.add(offset + "  this.storageStatePath = null;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public " + parentClass + " withStorageState(Path storageStatePath) {");
      output.add(offset + "  this.storageState = null;");
      output.add(offset + "  this.storageStatePath = storageStatePath;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if ("Route.continue.overrides.postData".equals(jsonPath)) {
      output.add(offset + "public ContinueOverrides withPostData(String postData) {");
      output.add(offset + "  this.postData = postData.getBytes(StandardCharsets.UTF_8);");
      output.add(offset + "  return this;");
      output.add(offset + "}");
    }
    if ("Route.fulfill.response.body".equals(jsonPath)) {
      output.add(offset + "public FulfillResponse withBody(byte[] body) {");
      output.add(offset + "  this.bodyBytes = body;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
    }
    if (name.equals("httpCredentials")) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(String username, String password) {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "(username, password);");
      output.add(offset + "  return this;");
    } else if (type.isNestedClass) {
      output.add(offset + "public " + type.toJava() + " set" + toTitle(name) + "() {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "();");
      output.add(offset + "  return this." + name + ";");
    } else if ("Page.Viewport".equals(type.toJava()) || "Viewport".equals(type.toJava())) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(int width, int height) {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "(width, height);");
      output.add(offset + "  return this;");
    } else if ("Browser.VideoSize".equals(type.toJava()) || "VideoSize".equals(type.toJava())) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(int width, int height) {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "(width, height);");
      output.add(offset + "  return this;");
    } else if ("Set<Keyboard.Modifier>".equals(type.toJava())) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(Keyboard.Modifier... modifiers) {");
      output.add(offset + "  this." + name + " = new HashSet<>(Arrays.asList(modifiers));");
      output.add(offset + "  return this;");
    } else {
      String paramType = type.toJava();
      if ("Boolean".equals(paramType)) {
        paramType = "boolean";
      } else if ("Integer".equals(paramType)) {
        paramType = "int";
      } else if ("Double".equals(paramType)) {
        paramType = "double";
      }
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(" + paramType + " " + name + ") {");
      output.add(offset + "  this." + name + " = " + name + ";");
      output.add(offset + "  return this;");
    }
    output.add(offset + "}");
  }
}

class Interface extends TypeDefinition {
  private final List<Method> methods = new ArrayList<>();
  private final List<Event> events = new ArrayList<>();
  private static String header = "/*\n" +
    " * Copyright (c) Microsoft Corporation.\n" +
    " *\n" +
    " * Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
    " * you may not use this file except in compliance with the License.\n" +
    " * You may obtain a copy of the License at\n" +
    " *\n" +
    " * http://www.apache.org/licenses/LICENSE-2.0\n" +
    " *\n" +
    " * Unless required by applicable law or agreed to in writing, software\n" +
    " * distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
    " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
    " * See the License for the specific language governing permissions and\n" +
    " * limitations under the License.\n" +
    " */\n" +
    "\n" +
    "package com.microsoft.playwright;\n";

  private static Set<String> allowedBaseInterfaces = new HashSet<>(asList("Browser", "JSHandle", "BrowserContext"));

  Interface(JsonObject jsonElement) {
    super(null, jsonElement);
    for (JsonElement item : jsonElement.getAsJsonArray("members")) {
      JsonObject memberJson = item.getAsJsonObject();
      switch (memberJson.get("kind").getAsString()) {
        case "method":
        // All properties are converted to methods in Java.
        case "property":
          if ("Playwright".equals(jsonName) && "errors".equals(memberJson.get("name").getAsString())) {
            continue;
          }
          methods.add(new Method(this, memberJson));
          break;
        case "event":
          events.add(new Event(this, memberJson));
          break;
        default:
          throw new RuntimeException("Unexpected member kind: " + memberJson.toString());
      }
    }
  }

  void writeTo(List<String> output, String offset) {
    output.add(header);
    if ("Playwright".equals(jsonName)) {
      output.add("import com.microsoft.playwright.impl.PlaywrightImpl;");
    }
    if (jsonName.equals("Route")) {
      output.add("import java.nio.charset.StandardCharsets;");
    }
    if ("Download".equals(jsonName)) {
      output.add("import java.io.InputStream;");
    }
    if (asList("Page", "Frame", "ElementHandle", "FileChooser", "Browser", "BrowserContext", "BrowserType", "Download", "Route", "Selectors", "Video").contains(jsonName)) {
      output.add("import java.nio.file.Path;");
    }
    output.add("import java.util.*;");
    if (asList("Page", "BrowserContext", "WebSocket", "Worker").contains(jsonName)) {
      output.add("import java.util.function.Consumer;");
    }
    if (asList("Page", "Frame", "BrowserContext", "WebSocket").contains(jsonName)) {
      output.add("import java.util.function.Predicate;");
    }
    if (asList("Page", "Frame", "BrowserContext").contains(jsonName)) {
      output.add("import java.util.regex.Pattern;");
    }
    output.add("");

    String implementsClause = "";
    if (jsonElement.getAsJsonObject().has("extends")) {
      String base = jsonElement.getAsJsonObject().get("extends").getAsString();
      if (allowedBaseInterfaces.contains(base)) {
        implementsClause = " extends " + base;
      }
    }

    writeJavadoc(output, offset, formattedComment());
    output.add("public interface " + jsonName + implementsClause + " {");
    offset = "  ";
    writeSharedTypes(output, offset);
    writeEvents(output, offset);
    super.writeTo(output, offset);
    for (Method m : methods) {
      m.writeTo(output, offset);
    }
    if ("Playwright".equals(jsonName)) {
      output.add("");
      output.add(offset + "static Playwright create() {");
      output.add(offset + "  return PlaywrightImpl.create();");
      output.add(offset + "}");
      output.add("");
      output.add(offset + "void close() throws Exception;");
    }
    output.add("}");
    output.add("\n");
  }

  private void writeEvents(List<String> output, String offset) {
    if (events.isEmpty()) {
      return;
    }
    for (Event e : events) {
      output.add("");
      e.writeListenerMethods(output, offset);
    }
    output.add("");
    for (Event e : events) {
      e.writeWaitForEventIfNeeded(output, offset);
    }
    output.add("");
  }

  private void writeSharedTypes(List<String> output, String offset) {
    switch (jsonName) {
      case "Dialog": {
        output.add(offset + "enum Type { ALERT, BEFOREUNLOAD, CONFIRM, PROMPT }");
        output.add("");
        break;
      }
      case "Mouse": {
        output.add(offset + "enum Button { LEFT, MIDDLE, RIGHT }");
        output.add("");
        break;
      }
      case "Keyboard": {
        output.add(offset + "enum Modifier { ALT, CONTROL, META, SHIFT }");
        output.add("");
        break;
      }
      case "Page": {
        output.add(offset + "class Viewport {");
        output.add(offset + "  private final int width;");
        output.add(offset + "  private final int height;");
        output.add("");
        output.add(offset + "  public Viewport(int width, int height) {");
        output.add(offset + "    this.width = width;");
        output.add(offset + "    this.height = height;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int width() {");
        output.add(offset + "    return width;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int height() {");
        output.add(offset + "    return height;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");

        output.add(offset + "interface Function {");
        output.add(offset + "  Object call(Object... args);");
        output.add(offset + "}");
        output.add("");

        output.add(offset + "interface Binding {");
        output.add(offset + "  interface Source {");
        output.add(offset + "    BrowserContext context();");
        output.add(offset + "    Page page();");
        output.add(offset + "    Frame frame();");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  Object call(Source source, Object... args);");
        output.add(offset + "}");
        output.add("");
        output.add(offset + "interface Error {");
        output.add(offset + "  String message();");
        output.add(offset + "  String name();");
        output.add(offset + "  String stack();");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "BrowserContext": {
        output.add(offset + "enum SameSite { STRICT, LAX, NONE }");
        output.add("");
        output.add(offset + "class HTTPCredentials {");
        output.add(offset + "  private final String username;");
        output.add(offset + "  private final String password;");
        output.add("");
        output.add(offset + "  public HTTPCredentials(String username, String password) {");
        output.add(offset + "    this.username = username;");
        output.add(offset + "    this.password = password;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public String username() {");
        output.add(offset + "    return username;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public String password() {");
        output.add(offset + "    return password;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        output.add(offset + "class StorageState {");
        output.add(offset + "  public List<AddCookie> cookies;");
        output.add(offset + "  public List<OriginState> origins;");
        output.add("");
        output.add(offset + "  public static class OriginState {");
        output.add(offset + "    public final String origin;");
        output.add(offset + "    public List<LocalStorageItem> localStorage;");
        output.add("");
        output.add(offset + "    public static class LocalStorageItem {");
        output.add(offset + "      public String name;");
        output.add(offset + "      public String value;");
        output.add(offset + "      public LocalStorageItem(String name, String value) {");
        output.add(offset + "        this.name = name;");
        output.add(offset + "        this.value = value;");
        output.add(offset + "      }");
        output.add(offset + "    }");
        output.add("");
        output.add(offset + "    public OriginState(String origin) {");
        output.add(offset + "      this.origin = origin;");
        output.add(offset + "    }");
        output.add("");
        output.add(offset + "    public OriginState withLocalStorage(List<LocalStorageItem> localStorage) {");
        output.add(offset + "      this.localStorage = localStorage;");
        output.add(offset + "      return this;");
        output.add(offset + "    }");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public StorageState() {");
        output.add(offset + "    cookies = new ArrayList<>();");
        output.add(offset + "    origins = new ArrayList<>();");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public List<AddCookie> cookies() {");
        output.add(offset + "    return this.cookies;");
        output.add(offset + "  }");
        output.add(offset + "  public List<OriginState> origins() {");
        output.add(offset + "    return this.origins;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "Browser": {
        output.add(offset + "class VideoSize {");
        output.add(offset + "  private final int width;");
        output.add(offset + "  private final int height;");
        output.add("");
        output.add(offset + "  public VideoSize(int width, int height) {");
        output.add(offset + "    this.width = width;");
        output.add(offset + "    this.height = height;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int width() {");
        output.add(offset + "    return width;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int height() {");
        output.add(offset + "    return height;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "ElementHandle": {
        output.add(offset + "class BoundingBox {");
        output.add(offset + "  public double x;");
        output.add(offset + "  public double y;");
        output.add(offset + "  public double width;");
        output.add(offset + "  public double height;");
        output.add(offset + "}");
        output.add("");
        output.add(offset + "class SelectOption {");
        output.add(offset + "  public String value;");
        output.add(offset + "  public String label;");
        output.add(offset + "  public Integer index;");
        output.add("");
        output.add(offset + "  public SelectOption withValue(String value) {");
        output.add(offset + "    this.value = value;");
        output.add(offset + "    return this;");
        output.add(offset + "  }");
        output.add(offset + "  public SelectOption withLabel(String label) {");
        output.add(offset + "    this.label = label;");
        output.add(offset + "    return this;");
        output.add(offset + "  }");
        output.add(offset + "  public SelectOption withIndex(int index) {");
        output.add(offset + "    this.index = index;");
        output.add(offset + "    return this;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "FileChooser": {
        output.add(offset + "class FilePayload {");
        output.add(offset + "  public final String name;");
        output.add(offset + "  public final String mimeType;");
        output.add(offset + "  public final byte[] buffer;");
        output.add("");
        output.add(offset + "  public FilePayload(String name, String mimeType, byte[] buffer) {");
        output.add(offset + "    this.name = name;");
        output.add(offset + "    this.mimeType = mimeType;");
        output.add(offset + "    this.buffer = buffer;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "WebSocket": {
        output.add(offset + "interface FrameData {");
        output.add(offset + "  byte[] body();");
        output.add(offset + "  String text();");
        output.add(offset + "}");
        output.add("");
        break;
      }
    }
  }
}

class NestedClass extends TypeDefinition {
  final String name;
  final List<Field> fields = new ArrayList<>();

  private static Set<String> deprecatedOptions = new HashSet<>();
  static {
    deprecatedOptions.add("Browser.newPage.options.videosPath");
    deprecatedOptions.add("Browser.newPage.options.videoSize");
    deprecatedOptions.add("Browser.newPage.options.logger");
    deprecatedOptions.add("Browser.newContext.options.videosPath");
    deprecatedOptions.add("Browser.newContext.options.videoSize");
    deprecatedOptions.add("Browser.newContext.options.logger");
    deprecatedOptions.add("BrowserType.launchPersistentContext.options.videosPath");
    deprecatedOptions.add("BrowserType.launchPersistentContext.options.videoSize");
    deprecatedOptions.add("BrowserType.launchPersistentContext.options.logger");
    deprecatedOptions.add("BrowserType.launch.options.logger");
  }


  NestedClass(Element parent, String name, JsonObject jsonElement) {
    super(parent, true, jsonElement);
    this.name = name;

    JsonObject jsonType = jsonElement;
    if ("union".equals(jsonName)) {
      for (JsonElement item : jsonType.getAsJsonArray("union")) {
        if (!"null".equals(item.getAsJsonObject().get("name").getAsString())) {
          jsonType = item.getAsJsonObject();
          break;
        }
      }
    }

    while (jsonType.has("templates")) {
      JsonArray params = jsonType.getAsJsonArray("templates");
      if (params.size() != 1) {
        throw new RuntimeException("Unexpected number of parameters: " + jsonElement);
      }
      jsonType = params.get(0).getAsJsonObject();
    }

    if (jsonType.has("properties")) {
      for (JsonElement item : jsonType.getAsJsonArray("properties")) {
        JsonObject propertyJson = item.getAsJsonObject();
        String propertyName = propertyJson.get("name").getAsString();
        if (deprecatedOptions.contains(jsonPath + "." + propertyName)) {
          continue;
        }
        fields.add(new Field(this, propertyName, propertyJson));
      }
    }
  }

  void writeTo(List<String> output, String offset) {
    String access = parent.typeScope() instanceof NestedClass ? "public " : "";
    output.add(offset + access + "class " + name + " {");
    String bodyOffset = offset + "  ";
    super.writeTo(output, bodyOffset);

    boolean isReturnType = parent.parent instanceof Method;
    String fieldAccess = isReturnType ? "private " : "public ";
    for (Field f : fields) {
      f.writeTo(output, bodyOffset, fieldAccess);
    }
    output.add("");
    if ("Request.failure".equals(jsonPath)) {
      writeConstructor(output, bodyOffset);
    }
    if (isReturnType) {
      for (Field f : fields) {
        f.writeGetter(output, bodyOffset);
      }
    } else {
      writeBuilderMethods(output, bodyOffset);
      if ("Browser.newContext.options".equals(jsonPath) ||
          "Browser.newPage.options".equals(jsonPath)) {
        writeDeviceDescriptorBuilder(output, bodyOffset);
      }
    }
    output.add(offset + "}");
  }

  private void writeBuilderMethods(List<String> output, String bodyOffset) {
    if (parent.typeScope() instanceof  NestedClass) {
      NestedClass outer = (NestedClass) parent.typeScope();
      output.add(bodyOffset + name + "() {");
      output.add(bodyOffset + "}");
      output.add(bodyOffset + "public " + outer.name + " done() {");
      output.add(bodyOffset + "  return " + outer.name + ".this;");
      output.add(bodyOffset + "}");
      output.add("");
    }
    for (Field f : fields) {
      f.writeBuilderMethod(output, bodyOffset, name);
    }
  }

  private void writeConstructor(List<String> output, String bodyOffset) {
    List<String> args = new ArrayList<>();
    for (Field f : fields) {
      args.add(f.type.toJava() + " " + f.name);
    }
    output.add(bodyOffset + "public " + name + "(" + String.join(", ", args) + ") {");
    for (Field f : fields) {
      output.add(bodyOffset + "  this." + f.name + " = " + f.name + ";");
    }
    output.add(bodyOffset + "}");
  }

  private void writeDeviceDescriptorBuilder(List<String> output, String bodyOffset) {
    output.add(bodyOffset + "public " + name + " withDevice(DeviceDescriptor device) {");
    output.add(bodyOffset + "  withViewport(device.viewport().width(), device.viewport().height());");
    output.add(bodyOffset + "  withUserAgent(device.userAgent());");
    output.add(bodyOffset + "  withDeviceScaleFactor(device.deviceScaleFactor());");
    output.add(bodyOffset + "  withIsMobile(device.isMobile());");
    output.add(bodyOffset + "  withHasTouch(device.hasTouch());");
    output.add(bodyOffset + "  return this;");
    output.add(bodyOffset + "}");
  }
}

class Enum extends TypeDefinition {
  final String name;
  final List<String> enumValues;

  Enum(TypeDefinition parent, String name, JsonObject jsonObject) {
    super(parent, jsonObject);
    this.name = name;
    enumValues = new ArrayList<>();
    for (JsonElement item : jsonObject.getAsJsonArray("union")) {
      String value = item.getAsJsonObject().get("name").getAsString();
      if ("null".equals(value)) {
        continue;
      }
      enumValues.add(value.substring(1, value.length() - 1).replace("-", "_").toUpperCase());
    }
  }

  void writeTo(List<String> output, String offset) {
    String access = parent.typeScope() instanceof NestedClass ? "public " : "";
    output.add(offset + access + "enum " + name + " { " + String.join(", ", enumValues) + " }");
  }
}

public class ApiGenerator {
  private static Set<String> skipList = new HashSet<>(Arrays.asList(
    "BrowserServer",
    "ChromiumBrowser",
    "ChromiumBrowserContext",
    "ChromiumCoverage",
    "CDPSession",
    "FirefoxBrowser",
    "Logger",
    "WebKitBrowser"
  ));

  ApiGenerator(Reader reader) throws IOException {
    JsonArray api = new Gson().fromJson(reader, JsonArray.class);
    File cwd = FileSystems.getDefault().getPath(".").toFile();
    File dir = new File(cwd, "playwright/src/main/java/com/microsoft/playwright");
    System.out.println("Writing files to: " + dir.getCanonicalPath());
    for (JsonElement entry: api) {
      String name = entry.getAsJsonObject().get("name").getAsString();
      if (skipList.contains(name)) {
        continue;
      }
      List<String> lines = new ArrayList<>();
      new Interface(entry.getAsJsonObject()).writeTo(lines, "");
      String text = String.join("\n", lines);
      try (FileWriter writer = new FileWriter(new File(dir, name + ".java"))) {
        writer.write(text);
      }
    }
  }

  public static void main(String[] args) throws IOException {
    File cwd = FileSystems.getDefault().getPath(".").toFile();
    System.out.println(cwd.getCanonicalPath());
    File file = new File(cwd, "tools/api-generator/src/main/resources/api.json");
    System.out.println("Reading from: " + file.getCanonicalPath());
    new ApiGenerator(new FileReader(file));
  }
}