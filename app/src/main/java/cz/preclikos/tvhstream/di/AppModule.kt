package cz.preclikos.tvhstream.di

import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.player.PlayerSession
import cz.preclikos.tvhstream.repositories.TvhRepository
import cz.preclikos.tvhstream.settings.SecurePasswordStore
import cz.preclikos.tvhstream.settings.SettingsStore
import cz.preclikos.tvhstream.viewmodels.AppConnectionViewModel
import cz.preclikos.tvhstream.viewmodels.ChannelsViewModel
import cz.preclikos.tvhstream.viewmodels.VideoPlayerViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single<CoroutineDispatcher>(qualifier = org.koin.core.qualifier.named("io")) { Dispatchers.IO }

    single { HtspService(ioDispatcher = get(named("io"))) }
    single { TvhRepository(htsp = get(), ioDispatcher = get(named("io"))) }

    single { SettingsStore(context = get()) }
    single { SecurePasswordStore(context = get()) }

    single { PlayerSession(get()) }

    viewModel {
        AppConnectionViewModel(
            htsp = get(),
            repo = get(),
            settings = get(),
            passwords = get()
        )
    }

    viewModel { ChannelsViewModel(htsp = get(), repo = get()) }
    viewModel { VideoPlayerViewModel(playerSession = get(), repo = get()) }

}
