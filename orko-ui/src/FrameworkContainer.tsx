/*
 * Orko
 * Copyright © 2018-2019 Graham Crockford
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
import React, { useState, useContext, useEffect, useMemo } from "react"
import Framework, { DragData } from "./Framework"
import theme from "./theme"
import { AuthContext, AuthApi } from "@orko-ui-auth/index"
import { DraggableData } from "react-rnd"
import Immutable from "seamless-immutable"
import useUiConfig, { Panel } from "./useUiConfig"
import { Layouts, Layout } from "react-grid-layout"
import { Coin } from "@orko-ui-market/index"

const windowToBreakpoint = (width: number) => (width < theme.lg ? (width < theme.md ? "sm" : "md") : "lg")

export interface FrameworkApi {
  paperTrading: boolean
  enablePaperTrading(): void
}

export const FrameworkContext = React.createContext<FrameworkApi>(null)

const FrameworkContainer: React.FC<any> = () => {
  const [breakpoint, setBreakpoint] = useState(windowToBreakpoint(window.innerWidth))
  const [width, setWidth] = useState(window.innerWidth)
  const [paperTrading, setPaperTrading] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [alertsShownForCoin, setAlertsShownForCoin] = useState<Coin>(null)
  const [uiConfig, uiConfigApi] = useUiConfig()
  const authApi: AuthApi = useContext(AuthContext)

  const api: FrameworkApi = useMemo(
    () => ({
      paperTrading,
      enablePaperTrading: () => setPaperTrading(true)
    }),
    [paperTrading, setPaperTrading]
  )

  useEffect(() => {
    window.addEventListener("resize", (e: UIEvent) => setWidth(window.innerWidth))
  }, [])

  const layoutsAsObject = uiConfig.layouts
  const layouts = useMemo<Layouts>(
    () =>
      Immutable({
        lg: Object.values(layoutsAsObject.lg),
        md: Object.values(layoutsAsObject.md),
        sm: Object.values(layoutsAsObject.sm)
      }),
    [layoutsAsObject]
  )

  const panelsAsObject = uiConfig.panels
  const panels = useMemo<Panel[]>(() => Immutable(Object.values(panelsAsObject)), [panelsAsObject])
  const hiddenPanels = useMemo<Panel[]>(
    () => (panels ? panels.filter(panel => !panel.visible) : Immutable([])),
    [panels]
  )

  return (
    <FrameworkContext.Provider value={api}>
      <Framework
        isMobile={breakpoint === "sm"}
        width={width}
        showSettings={showSettings}
        panels={panels}
        hiddenPanels={hiddenPanels}
        layouts={layouts}
        layoutsAsObj={layoutsAsObject[breakpoint]}
        onToggleViewSettings={() => setShowSettings(!showSettings)}
        onTogglePanelAttached={uiConfigApi.togglePanelAttached}
        onTogglePanelVisible={uiConfigApi.togglePanelVisible}
        onResetLayout={uiConfigApi.resetPanelsAndLayouts}
        onLayoutChange={(layout: Layout[], layouts: Layouts) => uiConfigApi.updateLayouts(layouts)}
        onMovePanel={(key: string, d: DraggableData) => uiConfigApi.movePanel(key, d.x, d.y)}
        onResizePanel={(key: string, d: DragData) => uiConfigApi.resizePanel(key, d.x, d.y, d.w, d.h)}
        onInteractPanel={(key: string) => uiConfigApi.panelToFront(key)}
        onBreakpointChange={(breakpoint: string) => setBreakpoint(breakpoint)}
        onLogout={authApi.logout}
        onClearWhitelisting={authApi.clearWhitelisting}
        alertsShownForCoin={alertsShownForCoin}
        onShowAlerts={setAlertsShownForCoin}
        onHideAlerts={() => setAlertsShownForCoin(null)}
      />
    </FrameworkContext.Provider>
  )
}

export default FrameworkContainer
