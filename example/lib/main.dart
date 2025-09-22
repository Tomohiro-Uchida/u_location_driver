import 'dart:ffi';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:u_location_driver/u_location_driver.dart';
import 'package:u_location_driver_example/send_to_host.dart';
import 'package:u_location_driver_example/write_to_file.dart';

@pragma('vm:entry-point')
Future<void> uLocationBackgroundHandler() async {
  print("Dart isolate started");
  debugPrint("Dart: Start uLocationBackgroundHandler()");
  WidgetsFlutterBinding.ensureInitialized();
  try {
    debugPrint("Dart: uLocationBackgroundHandler() -> Set Method Channel");
    MethodChannel toDartChannel = MethodChannel("com.jimdo.uchida001tmhr.u_location_driver/toDart");
    debugPrint("Dart: uLocationBackgroundHandler() -> setMethodCallHandler()");
    toDartChannel.setMethodCallHandler((call) {
      switch (call.method) {
        case "location":
          WriteToFile writeToFile = WriteToFile();
          writeToFile.write(call.arguments);
          SendToHost sendToHost = SendToHost();
          sendToHost.send(call.arguments);
          return Future.value("ACK");
        default:
          return Future.value("NAK");
      }
    });
  } catch (e, st) {
    debugPrint("Dart: uLocationBackgroundHandler exception: $e\n$st");
  }
}

Future<void> main() async {
  // The name must be main().
  debugPrint("Dart: main()");
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final uLocationDriverPlugin = ULocationDriver();
  MethodChannel toDartChannel = MethodChannel("com.jimdo.uchida001tmhr.u_location_driver/toDart");
  String messageFromNative = "Waiting for message form Native ...";
  TextEditingController textEditingControllerFrom = TextEditingController();
  TextEditingController textEditingControllerPassword = TextEditingController();
  TextEditingController textEditingControllerTo = TextEditingController();
  late SharedPreferences prefs;
  bool isEmailEnabled = false;

  @override
  void initState() {
    super.initState();

    toDartChannel.setMethodCallHandler((call) {
      switch (call.method) {
        case "location":
          WriteToFile writeToFile = WriteToFile();
          writeToFile.write(call.arguments);
          SendToHost sendToHost = SendToHost();
          sendToHost.send(call.arguments);
          setState(() {
            messageFromNative = call.arguments;
          });
          return Future.value("ACK");
        default:
          return Future.value("NAK");
      }
    });

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      SharedPreferences.getInstance().then((prefs) {
        this.prefs = prefs;
        bool? emailEnabled = prefs.getBool("emailEnabled");
        String? username = prefs.getString("fromAddress");
        String? password = prefs.getString("password");
        String? toAddress = prefs.getString("toAddress");
        if (emailEnabled != null &&
            username != null &&
            username.isNotEmpty &&
            password != null &&
            password.isNotEmpty &&
            toAddress != null &&
            toAddress.isNotEmpty) {
          setState(() {
            isEmailEnabled = emailEnabled;
            textEditingControllerFrom.text = username;
            textEditingControllerPassword.text = password;
            textEditingControllerTo.text = toAddress;
          });
        }
      });
    });
  }

  @override
  // Widget破棄時
  void dispose() {
    // 監視の終了を登録
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text("uLocationDriverPlugin")),
        body: Center(
          child: Column(
            children: [
              Row(
                children: [
                  Checkbox(
                    value: isEmailEnabled,
                    onChanged: (bool? value) {
                      setState(() {
                        isEmailEnabled = value!;
                        prefs.setBool("emailEnabled", value);
                      });
                    },
                  ),
                  Text("Email enabled"),
                ],
              ),
              TextFormField(
                decoration: InputDecoration(labelText: "From: "),
                controller: textEditingControllerFrom,
                keyboardType: TextInputType.emailAddress,
                onChanged: ((value) async {
                  textEditingControllerFrom.text = value;
                  prefs.setString("fromAddress", value);
                }),
              ),
              TextFormField(
                decoration: InputDecoration(labelText: "Password: "),
                controller: textEditingControllerPassword,
                keyboardType: TextInputType.visiblePassword,
                onChanged: ((value) async {
                  textEditingControllerPassword.text = value;
                  prefs.setString("password", value);
                }),
              ),
              TextFormField(
                decoration: InputDecoration(labelText: "To: "),
                controller: textEditingControllerTo,
                keyboardType: TextInputType.emailAddress,
                onChanged: ((value) async {
                  textEditingControllerTo.text = value;
                  prefs.setString("toAddress", value);
                }),
              ),
              TextButton(
                onPressed: (() {
                  uLocationDriverPlugin.initialize1();
                  debugPrint("Dart: initialize1");
                }),
                child: Text("Initialize1"),
              ),
              TextButton(
                onPressed: (() {
                  uLocationDriverPlugin.initialize2();
                  debugPrint("Dart: initialize2");
                }),
                child: Text("Initialize2"),
              ),
              TextButton(
                onPressed: (() {
                  final callbackHandle = PluginUtilities.getCallbackHandle(uLocationBackgroundHandler);
                  if (callbackHandle != null) {
                    final handle = callbackHandle.toRawHandle();
                    uLocationDriverPlugin.activate(handle);
                    debugPrint("Dart: activate");
                  }
                }),
                child: Text("Activate"),
              ),
              TextButton(
                onPressed: (() {
                  uLocationDriverPlugin.deactivate();
                  debugPrint("Dart: deactivate");
                }),
                child: Text("Deactivate"),
              ),
              Text(key: UniqueKey(), messageFromNative), // <- UniqueKey() must be used,
            ],
          ),
        ),
      ),
    );
  }
}
