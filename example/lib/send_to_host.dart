import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/cupertino.dart';
import 'package:mailer/mailer.dart' as mailer;
import 'package:mailer/smtp_server.dart';
import 'package:shared_preferences/shared_preferences.dart' as preferences;

class SendToHost {

  void send(String message) async {
    preferences.SharedPreferences prefs = await preferences.SharedPreferences.getInstance();
    bool? emailEnabled = prefs.getBool("emailEnabled");
    if (emailEnabled != null && emailEnabled) {
      final List<ConnectivityResult> connectivityResult = await (Connectivity().checkConnectivity());
      if (!connectivityResult.contains(ConnectivityResult.none)) {
        String? username = prefs.getString("fromAddress");
        String? password = prefs.getString("password");
        String? toAddress = prefs.getString("toAddress");
        if (username != null &&
            username.isNotEmpty &&
            password != null &&
            password.isNotEmpty &&
            toAddress != null &&
            toAddress.isNotEmpty) {
          SmtpServer smtpServer;
          if (Platform.isIOS) {
            smtpServer = SmtpServer("smtp.mail.me.com", port: 587, ssl: false, username: username, password: password);
          } else {
            smtpServer = SmtpServer("smtp.gmail.com", port: 587, ssl: false, username: username, password: password);
          }
          final mailMessage = mailer.Message()
            ..from = mailer.Address(username, '')
            ..recipients.addAll([toAddress])
            ..subject = "Message from Native"
            ..text = "Message from Native: $message";
          try {
            await mailer.send(mailMessage, smtpServer);
          } on mailer.MailerException catch (e) {
            debugPrint(e.toString());
          }
        }
      }
    }
  }
}
