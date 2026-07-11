import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import TrayPlayer from "./TrayPlayer";
import "./styles.css";
import "./tray-player.css";

const isTrayWindow = new URLSearchParams(window.location.search).get("window") === "tray";
if (isTrayWindow) document.documentElement.classList.add("tray-document");

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {isTrayWindow ? <TrayPlayer /> : <App />}
  </React.StrictMode>,
);
