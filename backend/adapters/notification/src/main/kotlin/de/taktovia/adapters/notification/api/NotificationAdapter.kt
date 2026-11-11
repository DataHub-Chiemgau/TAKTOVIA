package de.taktovia.adapters.notification.api

import de.taktovia.communicationsupport.api.DeliveryGateway

/**
 * Port des Adapters `notification` (Push, SMS, E-Mail): die Kanalschnittstelle [DeliveryGateway] des Moduls `communication-support`. Die Nachrichten-ID wird je Versuch
 * als Idempotenzreferenz an den Provider übergeben (WP-M2-32). Ein produktiver Provider folgt mit D-05 (Betrieb/Hosting bestimmt Push, SMS, E-Mail); gebaut und getestet
 * wird gegen den [SimulatorProvider].
 */
interface NotificationAdapter : DeliveryGateway
