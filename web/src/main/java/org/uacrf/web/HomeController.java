package org.uacrf.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {
  @GetMapping("/")
  String home(@AuthenticationPrincipal OidcUser user, Model model) {
    model.addAttribute("authenticated", user != null);
    if (user != null) {
      String fullName = user.getFullName();
      model.addAttribute(
          "displayName", fullName == null || fullName.isBlank() ? user.getSubject() : fullName);
      model.addAttribute("subject", user.getSubject());
    }
    return "home";
  }
}
